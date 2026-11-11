package com.campus.lf.module.announce;

import com.campus.lf.common.BizCode;
import com.campus.lf.module.announce.entity.Announcement;
import com.campus.lf.module.announce.mapper.AnnouncementMapper;
import com.campus.lf.support.InMemoryRedis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6 公告：状态机、时间窗（懒下架）、横幅口开关、公开/管理权限分离、防枚举 404。
 *
 * <p>种子前提：announcement id=1 已发布置顶（data.sql"欢迎使用"）——公开列表基线=1。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M6 公告 API")
class AnnouncementApiM6Test {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AnnouncementMapper annMapper;

    private static final String SEED_PWD = "password123";
    private static final String STU_A = "2023508090";   // 纯学生（无 announce:manage）
    private static final String MGR = "2023508091";     // MANAGER（announce:manage ✔）

    @TestConfiguration
    static class RedisTestConfig {
        @Bean @Primary
        public StringRedisTemplate stringRedisTemplate() {
            return InMemoryRedis.template();
        }
    }

    private JsonNode envelope(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    private String login(String no) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + no + "\",\"password\":\"" + SEED_PWD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return envelope(res).path("data").path("token").asText();
    }

    private long createDraft(String token, String title, String eff, String exp) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"content\":\"公告正文内容\""
                + (eff == null ? "" : ",\"effectiveAt\":\"" + eff + "\"")
                + (exp == null ? "" : ",\"expireAt\":\"" + exp + "\"") + "}";
        MvcResult r = mockMvc.perform(post("/api/admin/announcements").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return envelope(r).path("data").path("id").asLong();
    }

    private void patchStatus(String token, long id, int target) throws Exception {
        mockMvc.perform(patch("/api/admin/announcements/" + id + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":" + target + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("状态机：新公告恒草稿；草稿→下架非法 6002；发布→读者可见；下架→404")
    void stateMachine() throws Exception {
        String mgr = login(MGR);
        long id = createDraft(mgr, "图书馆延长开放", null, null);
        assertThat(annMapper.selectById(id).getStatus()).isEqualTo(0);

        // 草稿直接下架 → 非法迁移 409/6002
        mockMvc.perform(patch("/api/admin/announcements/" + id + "/status").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(BizCode.ANNOUNCEMENT_STATUS_INVALID.getCode()));

        // 读者：草稿不可见（详情 404/6001=与不存在同响应，防内部口径泄露）
        mockMvc.perform(get("/api/announcements/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(BizCode.ANNOUNCEMENT_NOT_FOUND.getCode()));

        patchStatus(mgr, id, 1);
        mockMvc.perform(get("/api/announcements/" + id))
                .andExpect(jsonPath("$.data.title").value("图书馆延长开放"));

        patchStatus(mgr, id, 2);
        mockMvc.perform(get("/api/announcements/" + id)).andExpect(status().isNotFound());
        // 下架→再发布合法（ALLOWED 表 2→1）
        patchStatus(mgr, id, 1);
        mockMvc.perform(get("/api/announcements/" + id)).andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("时间窗：未到生效不展示；已过生效展示；过期懒下架落库 status=2")
    void timeWindow() throws Exception {
        String mgr = login(MGR);
        long future = createDraft(mgr, "下周生效的公告", "2099-01-01T00:00:00", null);
        patchStatus(mgr, future, 1);
        mockMvc.perform(get("/api/announcements/" + future)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/announcements").param("pageSize", "50"))
                .andExpect(jsonPath("$.data.list[?(@.id==" + future + ")]").doesNotExist());

        long past = createDraft(mgr, "已过期公告", null, "2020-01-01T00:00:00");
        patchStatus(mgr, past, 1);
        mockMvc.perform(get("/api/announcements/" + past)).andExpect(status().isNotFound());
        // 懒下架：读一次后状态落库 2（时间语义在查询里，状态自愈留审计痕迹）
        assertThat(annMapper.selectById(past).getStatus()).isEqualTo(2);
        // 未到窗的那条仍是 1（effect 未到≠过期，不该被下架）
        assertThat(annMapper.selectById(future).getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("横幅口：置顶优先最新一条；announcement_enabled=0 全隐身但管理口不受影响")
    void bannerAndSwitch() throws Exception {
        String mgr = login(MGR);
        long fresh = createDraft(mgr, "最新公告", null, null);
        patchStatus(mgr, fresh, 1);
        // 种子置顶公告 id=1 在架：active 应取置顶优先 → 恒为 1
        MvcResult r = mockMvc.perform(get("/api/announcements/active")).andReturn();
        assertThat(envelope(r).path("data").get(0).path("id").asLong()).isEqualTo(1);

        // 把种子公告下架后，横幅给最新非置顶
        patchStatus(mgr, 1L, 2);
        r = mockMvc.perform(get("/api/announcements/active")).andReturn();
        assertThat(envelope(r).path("data").get(0).path("id").asLong()).isEqualTo(fresh);
        patchStatus(mgr, 1L, 1);   // 恢复种子（用例自治理，不靠回滚顺序）

        // 治理开关=0：读者横幅/列表空，管理列表照常
        jdbcTemplate.update("update sys_config set config_value='0' where config_key='announcement_enabled'");
        r = mockMvc.perform(get("/api/announcements/active")).andReturn();
        assertThat(envelope(r).path("data").size()).isEqualTo(0);
        mockMvc.perform(get("/api/admin/announcements").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("权限分离：学生管理口 403/1005；游客 401；读者口匿名 200")
    void permissionSplit() throws Exception {
        String stu = login(STU_A);
        mockMvc.perform(post("/api/admin/announcements").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"学生发公告\",\"content\":\"不行\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
        mockMvc.perform(get("/api/admin/announcements").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
        mockMvc.perform(delete("/api/admin/announcements/1"))
                .andExpect(status().isUnauthorized());
        // 读者口游客直通（种子公告在架）
        mockMvc.perform(get("/api/announcements/1")).andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("编辑：时间窗倒挂 400；外链图 400；合法编辑清旧图插新图；删除连带清图；陈旧版本 2004")
    void editAndGuards() throws Exception {
        String mgr = login(MGR);
        long id = createDraft(mgr, "改稿演练", null, null);

        // 校验失败类（窗倒挂/外链）——version 每次现读
        mockMvc.perform(put("/api/admin/announcements/" + id).header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"窗倒挂\",\"content\":\"正文\",\"version\":" + annMapper.selectById(id).getVersion()
                                + ",\"effectiveAt\":\"2027-01-01T00:00:00\",\"expireAt\":\"2026-01-01T00:00:00\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
        mockMvc.perform(put("/api/admin/announcements/" + id).header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"外链\",\"content\":\"正文\",\"version\":" + annMapper.selectById(id).getVersion()
                                + ",\"imageUrls\":[\"http://evil/a.png\"]}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));

        // 合法编辑（带 1 图）
        mockMvc.perform(put("/api/admin/announcements/" + id).header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"带图\",\"content\":\"正文\",\"version\":" + annMapper.selectById(id).getVersion()
                                + ",\"imageUrls\":[\"/uploads/2026/11/a.png\"]}"))
                .andExpect(jsonPath("$.code").value(0));
        Integer imgs = jdbcTemplate.queryForObject(
                "select count(*) from announcement_image where announcement_id=? and deleted=0", Integer.class, id);
        assertThat(imgs).isEqualTo(1);

        // 再编辑换图：整包替换（旧图行删净）
        mockMvc.perform(put("/api/admin/announcements/" + id).header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"换图\",\"content\":\"正文\",\"version\":" + annMapper.selectById(id).getVersion()
                                + ",\"imageUrls\":[\"/uploads/2026/11/b.png\"]}"))
                .andExpect(jsonPath("$.code").value(0));
        imgs = jdbcTemplate.queryForObject(
                "select count(*) from announcement_image where announcement_id=? and deleted=0", Integer.class, id);
        assertThat(imgs).isEqualTo(1);

        // 陈旧版本 → 2004（放在全部合法写之后：失败的 @Version UPDATE 在同事务里
        // 已把行版本 +1，重放旧版本号必然撞不上——这正是乐观锁的执法证据）
        mockMvc.perform(put("/api/admin/announcements/" + id).header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"改后\",\"content\":\"正文\",\"version\":" + 999 + "}"))
                .andExpect(jsonPath("$.code").value(BizCode.VERSION_CONFLICT.getCode()));

        // 删除连带清图
        mockMvc.perform(delete("/api/admin/announcements/" + id).header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        imgs = jdbcTemplate.queryForObject(
                "select count(*) from announcement_image where announcement_id=? and deleted=0", Integer.class, id);
        assertThat(imgs).isEqualTo(0);
    }

    @Test
    @DisplayName("详情浏览计数自增（读者口唯一计数点）")
    void viewCountOnDetail() throws Exception {
        int before = annMapper.selectById(1L).getViewCount();
        mockMvc.perform(get("/api/announcements/1")).andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/announcements/1")).andExpect(jsonPath("$.code").value(0));
        assertThat(annMapper.selectById(1L).getViewCount()).isEqualTo(before + 2);
        // 列表页不计数（口径：浏览=点进详情）
        mockMvc.perform(get("/api/announcements")).andExpect(jsonPath("$.code").value(0));
        assertThat(annMapper.selectById(1L).getViewCount()).isEqualTo(before + 2);
    }
}

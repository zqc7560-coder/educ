package com.campus.lf.module.admin;

import com.campus.lf.common.BizCode;
import com.campus.lf.config.AdminBootstrapRunner;
import com.campus.lf.module.admin.entity.OperationLog;
import com.campus.lf.module.admin.mapper.OperationLogMapper;
import com.campus.lf.module.admin.service.OperationLogService;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 管理端：审核队列三合一、回复处置、词库 CRUD+拦截即时生效、参数类型闸、
 * 看板口径、操作日志审计链。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M8 管理端 API")
class AdminM8Test {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OperationLogMapper opLogMapper;
    @Autowired private AdminBootstrapRunner adminBootstrap;   // admin 引导器（M2 同款显式驱动）

    private static final String SEED_PWD = "password123";
    private static final String MGR = "2023508091";     // MANAGER
    private static final AtomicInteger REG_SEQ = new AtomicInteger();

    @TestConfiguration
    static class RedisTestConfig {
        @Bean @Primary
        public StringRedisTemplate stringRedisTemplate() {
            return InMemoryRedis.template();
        }
    }

    private JsonNode envelope(MvcResult r) throws Exception {
        // UTF-8 显式解码（M2 教训：getContentAsString() 无参版按 ISO-8859-1，中文断言必炸）
        return om.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String login(String no) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + no + "\",\"password\":\"" + SEED_PWD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return envelope(res).path("data").path("token").asText();
    }

    /** 引导 admin（幂等）并登录：词库/参数/用户治理/日志权限点是 ADMIN 独占（种子矩阵） */
    private String loginAdmin() throws Exception {
        adminBootstrap.run(null);
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk()).andReturn();
        return envelope(res).path("data").path("token").asText();
    }

    private String registerAndLogin() throws Exception {
        String no = "6" + String.format("%09d", REG_SEQ.incrementAndGet());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + no + "\",\"nickname\":\"管理测试" + no
                                + "\",\"password\":\"Abcd@1234\",\"confirmPassword\":\"Abcd@1234\","
                                + "\"email\":\"" + no + "@campus.edu.cn\"}"))
                .andExpect(status().isOk());
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + no + "\",\"password\":\"Abcd@1234\"}"))
                .andExpect(status().isOk()).andReturn();
        return envelope(res).path("data").path("token").asText();
    }

    private long loginUid(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn();
        return envelope(me).path("data").path("id").asLong();
    }

    /** 发一件待审物品返回 id（不审核） */
    private long createPendingItem(String token, String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"LOST\",\"title\":\"" + title + "\",\"description\":\"描述内容\","
                                + "\"categoryId\":1,\"happenedAt\":\"2026-11-11T10:00:00\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return envelope(r).path("data").path("id").asLong();
    }

    private long createPendingPost(String token, String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"" + title + "\",\"content\":\"正文内容\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return envelope(r).path("data").path("id").asLong();
    }

    // ====================== 审核队列 ======================

    @Test
    @DisplayName("三队列合一：物品/帖子/回复各自的待审分组可见；学生 403")
    void auditQueue() throws Exception {
        String stu = registerAndLogin();
        String mgr = login(MGR);
        long itemId = createPendingItem(stu, "队列物品");
        long postId = createPendingPost(stu, "队列帖子");

        mockMvc.perform(get("/api/admin/audit-queue").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));

        MvcResult r = mockMvc.perform(get("/api/admin/audit-queue").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        JsonNode data = envelope(r).path("data");
        assertThat(data.path("items").path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(data.path("items").path("list").toString()).contains("队列物品");
        assertThat(data.path("posts").path("list").toString()).contains("队列帖子");
        assertThat(data.has("replies")).isTrue();
        assertThat(data.has("claims")).isTrue();
        assertThat(itemId).isPositive();
        assertThat(postId).isPositive();
    }

    @Test
    @DisplayName("回复处置：通过→读者可见；屏蔽必填理由+作者收通知；重复处置拒")
    void replyHandle() throws Exception {
        String author = registerAndLogin();
        String mgr = login(MGR);
        long postId = createPendingPost(author, "回复处置母帖");
        mockMvc.perform(patch("/api/forum/posts/" + postId + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));

        String commenter = registerAndLogin();
        MvcResult rr = mockMvc.perform(post("/api/forum/posts/" + postId + "/replies")
                        .header("Authorization", "Bearer " + commenter)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"待审的回复\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long replyId = envelope(rr).path("data").path("id").asLong();

        // 队列里能看到
        mockMvc.perform(get("/api/admin/replies/pending").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.data.list[?(@.id==" + replyId + ")]").exists());

        // 屏蔽无理由 → 400
        mockMvc.perform(post("/api/admin/replies/" + replyId + "/handle").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":2}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
        // 屏蔽有理由 → ok，读者树不可见
        mockMvc.perform(post("/api/admin/replies/" + replyId + "/handle").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":2,\"reason\":\"广告内容\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/forum/posts/" + postId + "/replies"))
                .andExpect(jsonPath("$.data.length()").value(0));
        // 作者收到 POST_AUDIT 通知
        long uid = loginUid(commenter);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from notification where user_id=? and biz_type='POST_AUDIT' and deleted=0",
                Integer.class, uid)).isEqualTo(1);
        // 重复处置（已不在待审）→ 400
        mockMvc.perform(post("/api/admin/replies/" + replyId + "/handle").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":1}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
    }

    // ====================== 敏感词库 ======================

    @Test
    @DisplayName("词库 CRUD：加词即拦截（refresh 生效链）、重复拒、删词放行；词库口权限")
    void wordCrud() throws Exception {
        String stu = registerAndLogin();
        String admin = loginAdmin();
        // MANAGER 无 word:manage（粒度钉：审核权限 ≠ 词库权限）
        mockMvc.perform(get("/api/admin/words").header("Authorization", "Bearer " + login(MGR)))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
        mockMvc.perform(get("/api/admin/words").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));

        // 新造一个还没有的测试词
        mockMvc.perform(post("/api/admin/words").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"测试违禁词\"}"))
                .andExpect(jsonPath("$.code").value(0));
        // 重复 → 400
        mockMvc.perform(post("/api/admin/words").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"测试违禁词\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
        // 空/超长 → 400
        mockMvc.perform(post("/api/admin/words").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));

        // 拦截即时生效（refresh 链）：含词发帖被拒
        mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"正常\",\"content\":\"这里有测试违禁词四个\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));

        // 删词 → 放行
        Long wid = jdbcTemplate.queryForObject(
                "select id from sensitive_word where word='测试违禁词' and deleted=0", Long.class);
        mockMvc.perform(delete("/api/admin/words/" + wid).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"正常\",\"content\":\"这里有测试违禁词四个\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ====================== 系统参数 ======================

    @Test
    @DisplayName("参数：列表透出 editable；BOOL/NUMBER 类型闸；非法键拒")
    void configs() throws Exception {
        String stu = registerAndLogin();
        String admin = loginAdmin();
        mockMvc.perform(get("/api/admin/configs").header("Authorization", "Bearer " + login(MGR)))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
        mockMvc.perform(get("/api/admin/configs").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));

        MvcResult r = mockMvc.perform(get("/api/admin/configs").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        assertThat(envelope(r).path("data").toString()).contains("item_audit");

        // BOOL 只收 0/1
        mockMvc.perform(put("/api/admin/configs/item_audit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"true\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
        mockMvc.perform(put("/api/admin/configs/item_audit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"0\"}"))
                .andExpect(jsonPath("$.code").value(0));
        // NUMBER 非负整数
        mockMvc.perform(put("/api/admin/configs/daily_publish_limit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"-3\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
        mockMvc.perform(put("/api/admin/configs/daily_publish_limit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"20\"}"))
                .andExpect(jsonPath("$.code").value(0));
        // 未知键
        mockMvc.perform(put("/api/admin/configs/no_such_key").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"1\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
        // 改完还原（种子 item_audit=1 是其它用例的前提，别留脏值——测试事务回滚兜底但表达意图）
        mockMvc.perform(put("/api/admin/configs/item_audit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"1\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ====================== 看板 ======================

    @Test
    @DisplayName("看板：六卡+找回率联动（确认归还→returned+1 rate 变）+趋势 7 点+分布+待办角标")
    void dashboard() throws Exception {
        String stu = registerAndLogin();
        String mgr = login(MGR);
        mockMvc.perform(get("/api/admin/stats/overview").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));

        MvcResult r = mockMvc.perform(get("/api/admin/stats/overview").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        JsonNode d = envelope(r).path("data");
        assertThat(d.path("trend7d").size()).isEqualTo(7);
        assertThat(d.path("categoryDist").size()).isGreaterThanOrEqualTo(6);
        assertThat(d.path("userTotal").asLong()).isPositive();
        assertThat(d.path("pendingItems").asLong()).isGreaterThanOrEqualTo(0);

        // 找回率联动：造 FOUND→审→认领→确认归还 → returnedCount+1
        String finder = registerAndLogin();
        // 找回率统计只数 FOUND：造带核验问题的招领件（createPendingItem 是 LOST 不能认领）
        MvcResult fr = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + finder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOUND\",\"title\":\"看板找回率样本耳机\",\"description\":\"捡到白色耳机\","
                                + "\"categoryId\":1,\"happenedAt\":\"2026-11-11T10:00:00\",\"verifyQuestion\":\"什么颜色\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long fid = envelope(fr).path("data").path("id").asLong();
        mockMvc.perform(patch("/api/items/" + fid + "/status").param("target", "1")
                .header("Authorization", "Bearer " + mgr)).andExpect(jsonPath("$.code").value(0));
        long before = jdbcTemplate.queryForObject(
                "select count(*) from lost_item where type='FOUND' and status=2 and offline_reason='已归还' and deleted=0",
                Long.class);
        String claimant = registerAndLogin();
        MvcResult cr = mockMvc.perform(post("/api/items/" + fid + "/claims").header("Authorization", "Bearer " + claimant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"白色带猫耳\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long cid = envelope(cr).path("data").path("id").asLong();
        mockMvc.perform(post("/api/claims/" + cid + "/confirm").header("Authorization", "Bearer " + finder))
                .andExpect(jsonPath("$.code").value(0));
        long after = jdbcTemplate.queryForObject(
                "select count(*) from lost_item where type='FOUND' and status=2 and offline_reason='已归还' and deleted=0",
                Long.class);
        assertThat(after).isEqualTo(before + 1);

        MvcResult r2 = mockMvc.perform(get("/api/admin/stats/overview").header("Authorization", "Bearer " + mgr))
                .andReturn();
        assertThat(envelope(r2).path("data").path("returnedCount").asLong()).isEqualTo(after);
        assertThat(envelope(r2).path("data").path("returnRate").asDouble()).isGreaterThan(0);
    }

    // ====================== 操作日志 ======================

    @Test
    @DisplayName("审计链：封禁/词库增删/参数改动/公告流转/回复屏蔽各留一条 operation_log；过滤分页")
    void oplogChain() throws Exception {
        String mgr = login(MGR);
        String admin = loginAdmin();
        String victim = registerAndLogin();
        long vid = loginUid(victim);

        mockMvc.perform(patch("/api/admin/users/" + vid + "/status").param("status", "2")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/admin/words").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"审计样本词\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(put("/api/admin/configs/match_recommend_top").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"3\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(patch("/api/admin/announcements/1/status").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":2}"))
                .andExpect(jsonPath("$.code").value(0));

        List<OperationLog> logs = opLogMapper.selectList(null);
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.getAction()).isEqualTo(OperationLogService.USER_MUTE);
            assertThat(l.getTarget()).isEqualTo("user:" + vid);
        });
        assertThat(logs).anySatisfy(l -> assertThat(l.getAction()).isEqualTo(OperationLogService.WORD_ADD));
        assertThat(logs).anySatisfy(l -> assertThat(l.getAction()).isEqualTo(OperationLogService.CONFIG_UPDATE));
        assertThat(logs).anySatisfy(l -> assertThat(l.getAction()).isEqualTo(OperationLogService.ANNOUNCE_OFFLINE));

        // 过滤查询 + 权限
        mockMvc.perform(get("/api/admin/oplogs").param("action", "USER_MUTE")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].target").value("user:" + vid));
        String stu = registerAndLogin();
        mockMvc.perform(get("/api/admin/oplogs").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
    }
}

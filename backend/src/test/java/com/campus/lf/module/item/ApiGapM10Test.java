package com.campus.lf.module.item;

import com.campus.lf.common.BizCode;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M10 后端缺口接口测试：/items/mine 路由存在性（UI 首测撞 500 的漏口钉死）、
 * 收藏 toggle 幂等、回收站三兄弟、上传口参数闸。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M10 后端补齐接口")
class ApiGapM10Test {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String SEED_PWD = "password123";
    private static final String STU_A = "2023508090";
    private static final String MGR = "2023508091";
    private static final AtomicInteger REG_SEQ = new AtomicInteger();

    @TestConfiguration
    static class RedisTestConfig {
        @Bean @Primary
        public StringRedisTemplate stringRedisTemplate() {
            return InMemoryRedis.template();
        }
    }

    private JsonNode envelope(MvcResult r) throws Exception {
        return om.readTree(new String(r.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private String login(String no) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + no + "\",\"password\":\"" + SEED_PWD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return envelope(res).path("data").path("token").asText();
    }

    private long createItem(String token, String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"LOST\",\"title\":\"" + title + "\",\"description\":\"描述内容\","
                                + "\"categoryId\":1,\"happenedAt\":\"2026-11-11T10:00:00\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return envelope(r).path("data").path("id").asLong();
    }

    private long createPost(String token, String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"" + title + "\",\"content\":\"收藏闸测试正文\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return envelope(r).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("收藏帖子状态闸：待审帖 5008 拒收藏；过审后可收藏；不存在帖 5001（与点赞/回复同口径）")
    void favoritePostGate() throws Exception {
        String stu = login(STU_A);
        String mgr = login(MGR);
        long postId = createPost(stu, "收藏闸样本帖");
        // 待审态（forum_audit=1 种子默认）：作者自己也不许收藏
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":" + postId + "}"))
                .andExpect(jsonPath("$.code").value(BizCode.POST_STATUS_INVALID.getCode()));
        // 审核通过后放行
        mockMvc.perform(patch("/api/forum/posts/" + postId + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":" + postId + "}"))
                .andExpect(jsonPath("$.data.favorited").value(true));
        // 不存在帖子：404/5001（防孤儿键，与收藏不存在物品同纪律）
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":999998}"))
                .andExpect(jsonPath("$.code").value(BizCode.POST_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("/api/items/mine 必须是路由而非被 /{id} 吞（UI 首测 500 回归钉）")
    void mineRoute() throws Exception {
        String stu = login(STU_A);
        createItem(stu, "mine路由样本");
        // 修复前：/mine 命中 /{id} 模板 → Long 转 "mine" 炸 NumberFormatException
        MvcResult mine = mockMvc.perform(get("/api/items/mine").header("Authorization", "Bearer " + stu))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        // 鲁棒口径：本用例的物品必须出现在自己视图里（跨类计数受共享 H2 影响不钉绝对值）
        assertThat(envelope(mine).path("data").toString()).contains("mine路由样本");
        // 游客：/api/items/* 在公开 GET 白名单里（Security 放行），匿名拒绝由
        // Controller 显式分支给信封 1001（HTTP 200+code=1001 是本项目统一口径）
        MvcResult anon = mockMvc.perform(get("/api/items/mine")).andReturn();
        assertThat(envelope(anon).path("code").asInt()).isEqualTo(BizCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("收藏 toggle：新增→取消幂等；坏 targetType 400；不存在物品 404")
    void favoriteToggle() throws Exception {
        String stu = login(STU_A);
        long id = createItem(stu, "收藏样本");
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":" + id + "}"))
                .andExpect(jsonPath("$.data.favorited").value(true));
        // 重复=取消（toggle 语义）
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":" + id + "}"))
                .andExpect(jsonPath("$.data.favorited").value(false));
        // 收藏不存在物品 → 404/2001（孤儿键防御）
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":999999}"))
                .andExpect(jsonPath("$.code").value(BizCode.ITEM_NOT_FOUND.getCode()));
        // 坏类型闸
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"WIFI\",\"targetId\":1}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));

        // 收藏后再查列表含之
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":" + id + "}"))
                .andExpect(jsonPath("$.data.favorited").value(true));
        mockMvc.perform(get("/api/favorites").param("type", "ITEM").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data[0].targetId").value(id));
    }

    @Test
    @DisplayName("回收站：删除进 trash→复活回原状态→再删→彻底删除物理行消失；学生 403")
    void trashFlow() throws Exception {
        String stu = login(STU_A);
        String mgr = login(MGR);
        long id = createItem(stu, "回收站样本");

        mockMvc.perform(delete("/api/items/" + id).header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0));
        // 学生看回收站 403（治理闸）
        mockMvc.perform(get("/api/items/trash").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));

        mockMvc.perform(get("/api/items/trash").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[?(@.id==" + id + ")]").exists());

        // 复活：回到"我的发布"（待审原状态）
        mockMvc.perform(post("/api/items/" + id + "/restore").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(jdbcTemplate.queryForObject(
                "select deleted from lost_item where id=" + id, Integer.class)).isZero();

        // 未删行 purge → 404（安全锁：deleted=1 条件不可能命中）
        mockMvc.perform(delete("/api/items/" + id + "/purge").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(BizCode.ITEM_NOT_FOUND.getCode()));

        // 删→purge：物理行消失
        mockMvc.perform(delete("/api/items/" + id).header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(delete("/api/items/" + id + "/purge").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from lost_item where id=" + id, Integer.class)).isZero();
    }

    @Test
    @DisplayName("上传口：无文件 400；非图片魔数 4001 链路由 multipart 走通")
    void uploadGuard() throws Exception {
        String stu = login(STU_A);
        MvcResult empty = mockMvc.perform(multipart("/api/files/images").file("files", new byte[0])
                        .header("Authorization", "Bearer " + stu)).andReturn();
        assertThat(envelope(empty).path("code").asInt()).isNotZero();  // 空文件在参数/类型层被拒
        // 伪装 png 的 HTML 内容：魔数不过
        var fake = new org.springframework.mock.web.MockMultipartFile(
                "files", "evil.png", "image/png", "<html>hi</html>".getBytes());
        mockMvc.perform(multipart("/api/files/images").file(fake)
                        .header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(BizCode.FILE_TYPE_INVALID.getCode()));
        // 真 PNG 头 8 字节 + 最小内容 → 通过（存 target 测试目录）
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4};
        var real = new org.springframework.mock.web.MockMultipartFile(
                "files", "a.png", "image/png", png);
        mockMvc.perform(multipart("/api/files/images").file(real)
                        .param("scene", "items").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.urls[0]").exists());
    }
}

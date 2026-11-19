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
    @Autowired private com.campus.lf.module.forum.service.ForumService forumService;

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

    private long loginUid(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn();
        return envelope(me).path("data").path("id").asLong();
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
        // 收藏闸（本类 favoriteItemGate 钉）：待审件不可收藏——此用例先过审再测 toggle 幂等
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + login(MGR)))
                .andExpect(jsonPath("$.code").value(0));
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
    @DisplayName("详情页相似推荐口（游客可访问）+ 置顶开关（item:top 治理权限矩阵）")
    void similarAndTop() throws Exception {
        String stu = login(STU_A);
        String mgr = login(MGR);           // MANAGER：种子矩阵含 item:top
        long a = createItem(stu, "相似源件A");
        // 过审 A 与候选件，才能进推荐（pageItems/similar 只见发布态）
        mockMvc.perform(patch("/api/items/" + a + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        long b = createItem(stu, "相似源件A");   // 同标题同分类→Jaccard 高
        mockMvc.perform(patch("/api/items/" + b + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));

        // 游客可读相似口（PUBLIC_GETS 登记回归钉：忘登记=401）
        MvcResult sim = mockMvc.perform(get("/api/items/" + a + "/similar")).andReturn();
        JsonNode env = envelope(sim);
        assertThat(env.path("code").asInt()).isEqualTo(0);
        assertThat(env.path("data").toString()).contains("相似源件A");

        // 置顶：学生无权 1005/403；管理员放行且真翻转 top_flag
        // 双通道兼容：@PreAuthorize 裸 403 或 service BizException 信封 1005（矩阵测契约不锁实现，M11 口径）
        MvcResult denied = mockMvc.perform(patch("/api/items/" + a + "/top")
                .header("Authorization", "Bearer " + stu)).andReturn();
        assertThat(denied.getResponse().getStatus() == 403
                || envelope(denied).path("code").asInt() == BizCode.FORBIDDEN.getCode())
                .as("学生置顶必须被拒").isTrue();
        mockMvc.perform(patch("/api/items/" + a + "/top").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        MvcResult det = mockMvc.perform(get("/api/items/" + a)).andReturn();
        assertThat(envelope(det).path("data").path("topFlag").asInt()).isEqualTo(1);
        // 再点=取消
        mockMvc.perform(patch("/api/items/" + a + "/top").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        MvcResult det2 = mockMvc.perform(get("/api/items/" + a)).andReturn();
        assertThat(envelope(det2).path("data").path("topFlag").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("物品图片上限=6：第 7 张后端拒（前端口径已对齐，双保险）")
    void itemImageCap() throws Exception {
        String stu = login(STU_A);
        StringBuilder urls = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            urls.append(i > 0 ? "," : "").append("\"/uploads/2026/1/cap").append(i).append(".png\"");
        }
        mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"LOST\",\"title\":\"图片上限件\",\"description\":\"描述\","
                                + "\"categoryId\":1,\"happenedAt\":\"2026-11-11T10:00:00\","
                                + "\"imageUrls\":[" + urls + "]}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("收藏/点赞同闸补全：待审物品不可收藏(2005 前后端同口径)；下架件也不可；已发布可")
    void favoriteItemGate() throws Exception {
        String stu = login(STU_A);
        String mgr = login(MGR);
        long pending = createItem(stu, "闸测待审件");   // item_audit=1 → status=0
        // 管理员也不能收藏待审件（用户实测 bug 回归钉：治理身份同样被闸）
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":" + pending + "}"))
                .andExpect(jsonPath("$.code").value(BizCode.ITEM_STATUS_INVALID.getCode()));
        // 过审后放行
        mockMvc.perform(patch("/api/items/" + pending + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":" + pending + "}"))
                .andExpect(jsonPath("$.data.favorited").value(true));
        // 下架后再新增收藏又被拒（取消已有收藏不受限=上面 favorited true 的 toggle 反向仍通）
        mockMvc.perform(patch("/api/items/" + pending + "/status").param("target", "2")
                        .param("reason", "闸测下架").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0));
        // 已持有收藏者（mgr）取消不受状态限制
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":" + pending + "}"))
                .andExpect(jsonPath("$.data.favorited").value(false));
        // 下架件再新增收藏被拒（stu 此前未收藏过）
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":" + pending + "}"))
                .andExpect(jsonPath("$.code").value(BizCode.ITEM_STATUS_INVALID.getCode()));
    }

    @Test
    @DisplayName("归一回复闸：对『待审二级楼』点回复(parentId 归一到已发布一级)→ 5008（用户实测路径钉）")
    void normalizedReplyToPendingFloorBlocked() throws Exception {
        String stu = login(STU_A);
        String mgr = login(MGR);
        MvcResult post = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/forum/posts").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":\"11\",\"title\":\"归一闸帖\",\"content\":\"归一回复漏洞验证正文\"}"))
                .andReturn();
        long pid = Long.parseLong(envelope(post).path("data").path("id").asText());
        mockMvc.perform(patch("/api/forum/posts/" + pid + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr)).andExpect(jsonPath("$.code").value(0));

        // 一级楼（管理员发，直接过审场景不可假设——发完由 MGR 放行）
        MvcResult r1 = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/forum/posts/" + pid + "/replies").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"一级楼\"}"))
                .andReturn();
        long root1 = Long.parseLong(envelope(r1).path("data").path("id").asText());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/replies/" + root1 + "/handle").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":1}"))
                .andExpect(jsonPath("$.code").value(0));

        // 二级待审楼：学生挂在已发布一级下（不审核，status=0）
        MvcResult r2 = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/forum/posts/" + pid + "/replies").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"二级待审楼\",\"parentId\":" + root1 + "}"))
                .andReturn();
        assertThat(envelope(r2).path("code").asInt()).isEqualTo(0);
        long pend2 = Long.parseLong(envelope(r2).path("data").path("id").asText());

        // 用户实测路径：管理员对这条"待审二级楼"点回复 → 前端归一 parentId=root1(已发布)
        // + replyToId=pend2(待审)。旧版只查 parentId → 放行=漏洞；新版 replyToId 闸必须 5008
        forumService.seedLastReplyAt(loginUid(mgr), 0L);   // mgr 发 root1 刚占防刷窗
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/forum/posts/" + pid + "/replies").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"绕归一抢跑\",\"parentId\":" + root1 + ",\"replyToId\":" + pend2 + "}"))
                .andExpect(jsonPath("$.code").value(BizCode.POST_STATUS_INVALID.getCode()));

        // 放行 pend2 后同参数回复 → 成功（闸只拦未过审目标）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/replies/" + pend2 + "/handle").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        // stu 刚被 5008 占防刷窗（5008 也记录时间戳）→ 用 MGR 发，注意 mgr 上一发也占窗；
        // 5008 尝试同样记录防刷时间戳——解锁后重发同参数：目标已过审 → 放行
        forumService.seedLastReplyAt(loginUid(mgr), 0L);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/forum/posts/" + pid + "/replies").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"放行后正常回复\",\"parentId\":" + root1 + ",\"replyToId\":" + pend2 + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("详情收藏态服务端回读：收藏→favorited=true，取消→false（刷新复位 bug 钉）")
    void detailFavoritedReadBack() throws Exception {
        String stu = login(STU_A);
        String mgr = login(MGR);
        long id = createItem(stu, "收藏回读件");
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr)).andExpect(jsonPath("$.code").value(0));
        // 初始：未收藏
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.favorited").value(false));
        // 收藏后回读 true
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":" + id + "}"))
                .andExpect(jsonPath("$.data.favorited").value(true));
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.favorited").value(true));
        // 他人视角各自独立
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.data.favorited").value(false));
        // 取消收藏回读 false
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"ITEM\",\"targetId\":" + id + "}"));
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.favorited").value(false));

        // 帖子同款：作者发布→MGR 收藏→MGR 读详情 true / 作者读 false
        MvcResult post = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/forum/posts").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":\"11\",\"title\":\"帖收藏回读\",\"content\":\"帖子收藏态回读验证正文\"}"))
                .andReturn();
        long pid = Long.parseLong(envelope(post).path("data").path("id").asText());
        mockMvc.perform(patch("/api/forum/posts/" + pid + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr)).andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/favorites/toggle").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":" + pid + "}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/forum/posts/" + pid).header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.data.favorited").value(true));
        mockMvc.perform(get("/api/forum/posts/" + pid).header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.favorited").value(false));
    }

    @Test
    @DisplayName("列表封面 coverUrl：首图口径、无图为 null、全状态列表各见其图")
    void listCoverUrl() throws Exception {
        String stu = login(STU_A);
        long id = createItem(stu, "封面样本件");   // 待审
        // 直挂两张图（绕过上传口）：sort_order=0 的必须赢
        jdbcTemplate.update("insert into item_image(item_id,url,sort_order,created_at,deleted) values(?,?,0,CURRENT_TIMESTAMP,0)",
                id, "/uploads/demoimg/first.png");
        jdbcTemplate.update("insert into item_image(item_id,url,sort_order,created_at,deleted) values(?,?,1,CURRENT_TIMESTAMP,0)",
                id, "/uploads/demoimg/second.png");

        // 作者"我的发布"：待审件也带封面（自己视角按图找物）
        MvcResult mine = mockMvc.perform(get("/api/items/mine").header("Authorization", "Bearer " + stu)).andReturn();
        String d = envelope(mine).path("data").path("list").toString();
        assertThat(d).contains("\"coverUrl\":\"/uploads/demoimg/first.png\"");

        // 大厅游客列表：该件未发布→不在列表；发布后→coverUrl 出现（首图，非第二图）
        mockMvc.perform(get("/api/items")).andReturn();  // 预热缓存键
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + login(MGR))).andExpect(jsonPath("$.code").value(0));
        // 发布状态变更会清列表缓存；这里 keyword 查询走 DB 直读路径避免缓存时序
        MvcResult hall = mockMvc.perform(get("/api/items").param("keyword", "封面样本件")).andReturn();
        String h = envelope(hall).path("data").path("list").toString();
        // 新契约：coverUrl=首图（列表封面位），images=整集有序（卡片内轮播用）——
        // 用户要求"每张卡片的封面图可轮播自己那组图，只有一张就不轮"
        assertThat(h).contains("\"coverUrl\":\"/uploads/demoimg/first.png\"");
        assertThat(h).contains("\"images\":[\"/uploads/demoimg/first.png\",\"/uploads/demoimg/second.png\"]");
    }

    @Test
    @DisplayName("互动闸补全：治理者也不可回复未发布帖/待审楼（实测 bug 回归钉）")
    void governReplyGate() throws Exception {
        String stu = login(STU_A);
        String mgr = login(MGR);
        // 待审帖
        MvcResult post = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/forum/posts").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":\"11\",\"title\":\"治理回复闸帖\",\"content\":\"待审状态治理者回复验证正文\"}"))
                .andReturn();
        long pid = Long.parseLong(envelope(post).path("data").path("id").asText());

        // MANAGER 回复待审帖 → 拒绝（此前 canGovern 开后门=实测 bug）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/forum/posts/" + pid + "/replies").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"管理员抢跑回复\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.POST_STATUS_INVALID.getCode()));

        // 过审 → 学生发一级评论（审核开关关时直接已发布）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/forum/posts/" + pid + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr)).andExpect(jsonPath("$.code").value(0));
        MvcResult r1 = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/forum/posts/" + pid + "/replies").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"一级正常评论\"}"))
                .andReturn();
        assertThat(envelope(r1).path("code").asInt()).isEqualTo(0);
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

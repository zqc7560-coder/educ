package com.campus.lf.module.forum;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.BizCode;
import com.campus.lf.module.forum.entity.ForumLike;
import com.campus.lf.module.forum.entity.ForumPost;
import com.campus.lf.module.forum.entity.ForumReply;
import com.campus.lf.module.forum.mapper.ForumLikeMapper;
import com.campus.lf.module.forum.mapper.ForumPostMapper;
import com.campus.lf.module.forum.mapper.ForumReplyMapper;
import com.campus.lf.module.forum.service.ForumService;
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
 * M5 论坛：发帖审核链、两级回复封顶、10s 防刷、幂等点赞、举报转审、编辑重审、日限合计。
 *
 * <p>类级 @Transactional（M4 教训：API 测试必须回滚，防跨类污染共享 H2 的全局闸门计数）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M5 论坛 API")
class ForumApiM5Test {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ForumPostMapper postMapper;
    @Autowired private com.campus.lf.module.forum.mapper.PostReportMapper reportMapper;
    @Autowired private ForumReplyMapper replyMapper;
    @Autowired private ForumLikeMapper likeMapper;
    @Autowired private ForumService forumService;

    private static final String SEED_PWD = "password123";
    private static final String STU_A = "2023508090";   // 学生
    private static final String STU_B = "2023508091";   // 学生+MANAGER(forum:audit/offline)
    private static final AtomicInteger REG_SEQ = new AtomicInteger();

    @TestConfiguration
    static class RedisTestConfig {
        @Bean @Primary
        public StringRedisTemplate stringRedisTemplate() {
            return InMemoryRedis.template();
        }
    }

    // ====================== 工具 ======================

    private JsonNode envelope(MvcResult r) throws Exception {
        return om.readTree(new String(r.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8));  // M3 教训：无参版按 ISO-8859-1
    }

    private String login(String studentNo, String pwd) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + studentNo + "\",\"password\":\"" + pwd + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return envelope(res).path("data").path("token").asText();
    }

    private String login(String studentNo) throws Exception {
        return login(studentNo, SEED_PWD);
    }

    private long loginUid(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn();
        return envelope(me).path("data").path("id").asLong();
    }

    /** 注册新鲜账号（8 前缀学号）并登录，返回 token */
    private String registerAndLogin() throws Exception {
        String no = "8" + String.format("%09d", REG_SEQ.incrementAndGet());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + no + "\",\"nickname\":\"论坛测试" + no
                                + "\",\"password\":\"Abcd@1234\",\"confirmPassword\":\"Abcd@1234\","
                                + "\"email\":\"" + no + "@campus.edu.cn\"}"))
                .andExpect(status().isOk());
        return login(no, "Abcd@1234");
    }

    private long createPost(String token, String title, String content) throws Exception {
        return createPost(token, title, content, null);
    }

    /** 发帖（默认待审），返回 id；images 传 null 则不带图 */
    private long createPost(String token, String title, String content, List<String> images) throws Exception {
        StringBuilder body = new StringBuilder("{\"boardId\":11,\"title\":\"").append(title)
                .append("\",\"content\":\"").append(content).append("\"");
        if (images != null) {
            body.append(",\"imageUrls\":[");
            for (int i = 0; i < images.size(); i++) {
                body.append(i > 0 ? "," : "").append("\"").append(images.get(i)).append("\"");
            }
            body.append("]");
        }
        body.append("}");
        MvcResult r = mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return envelope(r).path("data").path("id").asLong();
    }

    private void publish(String mgrToken, long postId) throws Exception {
        mockMvc.perform(patch("/api/forum/posts/" + postId + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgrToken))
                .andExpect(jsonPath("$.code").value(0));
    }

    private long reply(String token, long postId, Long parentId, String content) throws Exception {
        String body = "{\"content\":\"" + content + "\"" +
                (parentId == null ? "}" : ",\"parentId\":" + parentId + "}");
        MvcResult r = mockMvc.perform(post("/api/forum/posts/" + postId + "/replies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return envelope(r).path("data").path("id").asLong();
    }

    /** 释放防刷闸（回拨时间戳=等效等过 10s 窗口；窗口语义本身在 replyRateLimit 用例钉） */
    private void unlockReply(String token) throws Exception {
        forumService.seedLastReplyAt(loginUid(token), 0L);
    }

    // ====================== 发帖与审核链 ======================

    @Test
    @DisplayName("发帖默认待审：作者可见、他人 404、干事审核后可见且 publishedAt 落值")
    void publishFlow() throws Exception {
        String author = login(STU_A);
        String other = registerAndLogin();
        String mgr = login(STU_B);
        long id = createPost(author, "捡到一张饭卡", "二号食堂门口，卡面姓王");

        ForumPost p = postMapper.selectById(id);
        assertThat(p.getStatus()).isEqualTo(ForumService.STATUS_PENDING);
        assertThat(p.getPublishedAt()).isNull();

        mockMvc.perform(get("/api/forum/posts/" + id).header("Authorization", "Bearer " + author))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/forum/posts/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(BizCode.POST_NOT_FOUND.getCode()));
        mockMvc.perform(get("/api/forum/posts").param("boardId", "11"))
                .andExpect(jsonPath("$.data.total").value(0));

        publish(mgr, id);
        mockMvc.perform(get("/api/forum/posts/" + id))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.publishedAt").exists());
        mockMvc.perform(get("/api/forum/posts").param("boardId", "11"))
                .andExpect(jsonPath("$.data.total").value(1));
        // 列表摘要化：详情才有 images 键（详情=全文口径的旁证）
        mockMvc.perform(get("/api/forum/posts/" + id))
                .andExpect(jsonPath("$.data.content").value("二号食堂门口，卡面姓王"))
                .andExpect(jsonPath("$.data.images").isArray());
    }

    @Test
    @DisplayName("编辑即重审（forum_audit=1）：过审帖改文后回待审；陈旧版本编辑 2004")
    void editTriggersReaudit() throws Exception {
        String author = login(STU_A);
        String mgr = login(STU_B);
        long id = createPost(author, "正常标题", "正常内容");
        publish(mgr, id);

        ForumPost before = postMapper.selectById(id);
        mockMvc.perform(put("/api/forum/posts/" + id).header("Authorization", "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"改后标题\",\"content\":\"养权重后改成违规文\","
                                + "\"version\":" + before.getVersion() + "}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(postMapper.selectById(id).getStatus()).isEqualTo(ForumService.STATUS_PENDING);

        mockMvc.perform(put("/api/forum/posts/" + id).header("Authorization", "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"再改\",\"content\":\"内容\",\"version\":"
                                + before.getVersion() + "}"))
                .andExpect(jsonPath("$.code").value(BizCode.VERSION_CONFLICT.getCode()));
    }

    @Test
    @DisplayName("图片闸门：外链 400、10 张超限 400、9 张合法整包入库并回显")
    void imageGuards() throws Exception {
        String author = login(STU_A);
        mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"外链图\",\"content\":\"x\",\"imageUrls\":[\"http://evil/x.png\"]}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));

        StringBuilder ten = new StringBuilder();
        for (int i = 0; i < 10; i++) ten.append(i > 0 ? "," : "").append("\"/uploads/2026/11/f").append(i).append(".png\"");
        mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"十张图\",\"content\":\"x\",\"imageUrls\":[" + ten + "]}"))
                .andExpect(status().isBadRequest());   // DTO @Size(max=9) 在 @Valid 层截

        StringBuilder nine = new StringBuilder();
        for (int i = 0; i < 9; i++) nine.append(i > 0 ? "," : "").append("\"/uploads/2026/11/g").append(i).append(".png\"");
        MvcResult r = mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"九宫格\",\"content\":\"x\",\"imageUrls\":[" + nine + "]}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long id = envelope(r).path("data").path("id").asLong();
        mockMvc.perform(get("/api/forum/posts/" + id).header("Authorization", "Bearer " + author))
                .andExpect(jsonPath("$.data.images.length()").value(9));
    }

    // ====================== 两级回复 ======================

    @Test
    @DisplayName("两级封顶：一级+二级 OK，给二级再挂 5006；跨帖父 400；树形装配")
    void twoLevelCap() throws Exception {
        String author = login(STU_A);
        String mgr = login(STU_B);
        long postId = createPost(author, "求问自习占座", "图书馆几点放座？");
        publish(mgr, postId);

        long r1 = reply(mgr, postId, null, "我们这届都是 7 点");
        unlockReply(mgr);
        unlockReply(author);
        long r2 = reply(author, postId, r1, "感谢！");
        assertThat(replyMapper.selectById(r2).getParentId()).isEqualTo(r1);

        String third = registerAndLogin();
        // 给二级回复再挂 → 5006（两级封顶，API 直连也拦得住）
        mockMvc.perform(post("/api/forum/posts/" + postId + "/replies").header("Authorization", "Bearer " + third)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"再挂一层\",\"parentId\":" + r2 + "}"))
                .andExpect(jsonPath("$.code").value(BizCode.REPLY_LEVEL_EXCEEDED.getCode()));
        // 跨帖父 → 400（注：上一条 5006 尝试已占用 third 的防刷时间戳——
        // 闸在业务校验之前记录，这是"防刷优先于业务判定"的顺序纪律，需先解锁）
        unlockReply(third);
        long otherPost = createPost(third, "另一个帖", "正文内容");
        publish(mgr, otherPost);   // 不发布则先撞"帖未发布不可回复"(5008)——跨帖父校验在其后
        mockMvc.perform(post("/api/forum/posts/" + otherPost + "/replies").header("Authorization", "Bearer " + third)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"跨帖\",\"parentId\":" + r1 + "}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));

        // 树形：1 根 1 子 + @昵称回填
        mockMvc.perform(get("/api/forum/posts/" + postId + "/replies").header("Authorization", "Bearer " + author))
                .andExpect(jsonPath("$.data[0].children.length()").value(1))
                .andExpect(jsonPath("$.data[0].children[0].content").value("感谢！"))
                .andExpect(jsonPath("$.data[0].children[0].replyToNickname").value("李同学"));
        // 计数不变式：reply_count=本帖未删回复数（提交即+1，不分审核状态）
        assertThat(postMapper.selectById(postId).getReplyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("10s 防刷：同人窗口内二连击 5007；窗口回拨后放行")
    void replyRateLimit() throws Exception {
        String author = login(STU_A);
        String mgr = login(STU_B);
        long postId = createPost(author, "防刷测试帖", "正文");
        publish(mgr, postId);

        String repeater = registerAndLogin();
        reply(repeater, postId, null, "第一条");
        mockMvc.perform(post("/api/forum/posts/" + postId + "/replies").header("Authorization", "Bearer " + repeater)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"秒发第二条\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.REPLY_TOO_FAST.getCode()));
        unlockReply(repeater);   // 模拟等过窗口
        reply(repeater, postId, null, "窗口后再发");
    }

    // ====================== 点赞幂等 ======================

    @Test
    @DisplayName("点赞幂等：重复点不重复计数、取消再点归位、双取消不为负、游客 401")
    void likeIdempotent() throws Exception {
        String author = login(STU_A);
        String mgr = login(STU_B);
        long postId = createPost(author, "值得赞的帖子", "干货");
        publish(mgr, postId);

        String liker = registerAndLogin();
        mockMvc.perform(post("/api/forum/posts/" + postId + "/like").header("Authorization", "Bearer " + liker))
                .andExpect(jsonPath("$.data.liked").value(true));
        mockMvc.perform(post("/api/forum/posts/" + postId + "/like").header("Authorization", "Bearer " + liker));
        assertThat(postMapper.selectById(postId).getLikeCount()).isEqualTo(1);
        assertThat(likeMapper.selectCount(new LambdaQueryWrapper<ForumLike>()
                .eq(ForumLike::getPostId, postId))).isEqualTo(1L);

        mockMvc.perform(delete("/api/forum/posts/" + postId + "/like").header("Authorization", "Bearer " + liker))
                .andExpect(jsonPath("$.data.liked").value(false));
        assertThat(postMapper.selectById(postId).getLikeCount()).isEqualTo(0);
        mockMvc.perform(delete("/api/forum/posts/" + postId + "/like").header("Authorization", "Bearer " + liker));
        mockMvc.perform(delete("/api/forum/posts/" + postId + "/like").header("Authorization", "Bearer " + liker));
        assertThat(postMapper.selectById(postId).getLikeCount()).isEqualTo(0);

        mockMvc.perform(post("/api/forum/posts/" + postId + "/like"))
                .andExpect(status().isUnauthorized());
    }

    // ====================== 举报工单（新语义：不即时改帖子状态） ======================

    @Test
    @DisplayName("举报=登记工单+帖子保持已发布；重复举报 5005；治理员可见待处理队列；保留/下架均销单；举报自己 400")
    void reportTicketFlow() throws Exception {
        String author = login(STU_A);
        String mgr = login(STU_B);
        long postId = createPost(author, "疑似广告帖", "加微信领奖 xxx");
        publish(mgr, postId);

        String reporter = registerAndLogin();
        mockMvc.perform(post("/api/forum/posts/" + postId + "/report").header("Authorization", "Bearer " + reporter)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"疑似微商广告\"}"))
                .andExpect(jsonPath("$.code").value(0));
        // 核心新语义：举报后帖子仍是已发布（不因个别举报在线消失）
        assertThat(postMapper.selectById(postId).getStatus()).isEqualTo(ForumService.STATUS_PUBLISHED);
        // 重复举报幂等拒
        mockMvc.perform(post("/api/forum/posts/" + postId + "/report").header("Authorization", "Bearer " + reporter)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"再报一次\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.REPORT_DUPLICATE.getCode()));
        // 自举报=意图错 400
        mockMvc.perform(post("/api/forum/posts/" + postId + "/report").header("Authorization", "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"自举报\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
        // 治理员可见举报队列
        mockMvc.perform(get("/api/forum/reports/pending").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.data.list[?(@.postId==" + postId + ")].reason").value("疑似微商广告"));
        // 普通学生读队列=403（含未过审理由，治理面）
        mockMvc.perform(get("/api/forum/reports/pending").header("Authorization", "Bearer " + reporter))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
        // 审查保留：帖子不动，工单销账
        mockMvc.perform(post("/api/forum/posts/" + postId + "/reports/keep").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(postMapper.selectById(postId).getStatus()).isEqualTo(ForumService.STATUS_PUBLISHED);
        // 工单已销账（status=1），待处理队列不再出现该帖
        List<com.campus.lf.module.forum.entity.PostReport> reps = reportMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                        com.campus.lf.module.forum.entity.PostReport>()
                        .eq(com.campus.lf.module.forum.entity.PostReport::getPostId, postId));
        assertThat(reps).allMatch(r -> r.getStatus() == 1);
        mockMvc.perform(get("/api/forum/reports/pending").param("pageSize", "50").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ====================== 越权与治理 ======================

    @Test
    @DisplayName("越权矩阵：他人编辑 403、无权限治理 403、楼主删他人回复合法且计数回冲")
    void governanceMatrix() throws Exception {
        String author = login(STU_A);
        String mgr = login(STU_B);
        long postId = createPost(author, "治理样本帖", "正文");
        publish(mgr, postId);

        String stranger = registerAndLogin();
        ForumPost p = postMapper.selectById(postId);
        mockMvc.perform(put("/api/forum/posts/" + postId).header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"篡改\",\"content\":\"篡改\",\"version\":" + p.getVersion() + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/forum/posts/" + postId + "/status").param("target", "2")
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"违规\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));

        reply(stranger, postId, null, "无礼的回复");
        ForumReply r = replyMapper.selectOne(new LambdaQueryWrapper<ForumReply>()
                .eq(ForumReply::getPostId, postId).orderByDesc(ForumReply::getId).last("limit 1"));
        mockMvc.perform(delete("/api/forum/replies/" + r.getId()).header("Authorization", "Bearer " + author))
                .andExpect(jsonPath("$.code").value(0));
        // 计数口径=本帖未删回复数（+1/-1 对称，不分审核状态，见 service 注释）
        assertThat(postMapper.selectById(postId).getReplyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("敏感词命中 PARAM_ERROR(与 M3 同口径)；板块不存在 5003")
    void wordGateAndBoard() throws Exception {
        String author = login(STU_A);
        mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"代刷课服务\",\"content\":\"专业代刷，私聊加我下单\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
        mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":99,\"title\":\"坏板块\",\"content\":\"正文内容\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.BOARD_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("日限合计（物品+帖子一个池子）：limit 收紧后第 3 发 429")
    void dailyLimitCrossModule() throws Exception {
        String fresh = registerAndLogin();
        long uid = loginUid(fresh);
        // 测试事务内收紧该用户视角的全局上限（回滚不留痕）
        jdbcTemplate.update("update sys_config set config_value='2' where config_key='daily_publish_limit'");

        createPost(fresh, "第一帖", "正文一二三");
        mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + fresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"LOST\",\"title\":\"第一个物品\",\"description\":\"描述内容\","
                                + "\"categoryId\":1,\"happenedAt\":\"2026-11-11T10:00:00\"}"))
                .andExpect(jsonPath("$.code").value(0));
        // 第 3 发（1 帖+1 物品已合计 2）→ 上限
        mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + fresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardId\":11,\"title\":\"第三发\",\"content\":\"应该被拦\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
        assertThat(uid).isPositive();
    }
}

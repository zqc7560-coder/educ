package com.campus.lf.module.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.BizCode;
import com.campus.lf.module.notice.NotifyType;
import com.campus.lf.module.notice.entity.Notification;
import com.campus.lf.module.notice.mapper.NotificationMapper;
import com.campus.lf.module.notice.service.MatchService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7 消息通知：匹配算法纯函数、发布→匹配推送链、收件箱全操作、群发权限、审核联动通知。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M7 消息通知 API")
class NotificationApiM7Test {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NotificationMapper notifMapper;
    @Autowired private MatchService matchService;
    @Autowired private NotificationService notificationService;

    private static final String SEED_PWD = "password123";
    private static final String MGR = "2023508091";     // MANAGER（item:audit）
    private static final AtomicInteger REG_SEQ = new AtomicInteger();

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

    private String login(String no, String pwd) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + no + "\",\"password\":\"" + pwd + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return envelope(res).path("data").path("token").asText();
    }

    private String login(String no) throws Exception {
        return login(no, SEED_PWD);
    }

    private String registerAndLogin() throws Exception {
        String no = "7" + String.format("%09d", REG_SEQ.incrementAndGet());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + no + "\",\"nickname\":\"消息测试" + no
                                + "\",\"password\":\"Abcd@1234\",\"confirmPassword\":\"Abcd@1234\","
                                + "\"email\":\"" + no + "@campus.edu.cn\"}"))
                .andExpect(status().isOk());
        return login(no, "Abcd@1234");
    }

    private long loginUid(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn();
        return envelope(me).path("data").path("id").asLong();
    }

    /** 发布并审核通过一件物品（type/标题/描述/分类/楼栋/日期可控），返回 item id */
    private long publishItem(String token, String mgrToken, String type, String title,
                             String desc, long categoryId, Long buildingId, LocalDateTime happened) throws Exception {
        StringBuilder body = new StringBuilder("{\"type\":\"").append(type)
                .append("\",\"title\":\"").append(title)
                .append("\",\"description\":\"").append(desc)
                .append("\",\"categoryId\":").append(categoryId)
                .append(",\"happenedAt\":\"").append(happened).append("\"");
        if (buildingId != null) {
            body.append(",\"buildingId\":").append(buildingId);
        }
        if ("FOUND".equals(type)) {
            body.append(",\"verifyQuestion\":\"它长什么样\"");
        }
        body.append("}");
        MvcResult r = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long id = envelope(r).path("data").path("id").asLong();
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgrToken))
                .andExpect(jsonPath("$.code").value(0));
        return id;
    }

    // ====================== 算法纯函数 ======================

    @Test
    @DisplayName("匹配纯函数：bigram 分词/Jaccard/时间衰减/阈值边界")
    void pureFunctions() {
        Set<String> a = MatchService.tokenize("捡到黑色钱包一个");
        Set<String> b = MatchService.tokenize("黑色钱包丢了");
        assertThat(MatchService.jaccard(a, b)).isGreaterThan(0.15);   // bigram "黑色"/"色钱"/"钱包" 重合
        assertThat(MatchService.jaccard(Set.of(), b)).isZero();

        // ascii 连续段成词且小写归一
        assertThat(MatchService.tokenize("AirPods Pro3")).contains("airpods", "pro3");

        // 时间分：≤3d 20 / ≤7d 10 / ≤14d 5 / 更远 0 / null 0
        LocalDateTime now = LocalDateTime.now();
        assertThat(MatchService.timeScore(now, now.minusDays(2))).isEqualTo(20);
        assertThat(MatchService.timeScore(now, now.minusDays(5))).isEqualTo(10);
        assertThat(MatchService.timeScore(now, now.minusDays(10))).isEqualTo(5);
        assertThat(MatchService.timeScore(now, now.minusDays(30))).isZero();
        assertThat(MatchService.timeScore(now, null)).isZero();
    }

    // ====================== 匹配推送链 ======================

    @Test
    @DisplayName("发布失物→匹配在架招领：FOUND 发布者收到 MATCH，低相似不推")
    void matchPushChain() throws Exception {
        String foundGuy = registerAndLogin();
        String lostGuy = registerAndLogin();
        String mgr = login(MGR);
        LocalDateTime d = LocalDateTime.now().minusDays(1);

        // 在架 FOUND：黑色钱包
        long foundId = publishItem(foundGuy, mgr, "FOUND", "捡到黑色钱包一个", "图书馆二楼座位上有黑色皮质钱包", 1, 13L, d);
        // 新发 LOST：黑色钱包 → 审核通过的瞬间触发匹配：**推给失主本人**（lostGuy），
        // 内容是在架对端（foundId）——"你丢的东西，这里有个相似的招领"
        publishItem(lostGuy, mgr, "LOST", "黑色钱包丢了急", "在图书馆二楼丢了黑色钱包内有校园卡", 1, 13L, d);

        List<Notification> hits = notifMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, loginUid(lostGuy))
                .eq(Notification::getBizType, NotifyType.MATCH));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getBizId()).isEqualTo(foundId);
        assertThat(hits.get(0).getTitle()).contains("匹配度");

        // 不相似的另一件 LOST（不同分类/楼栋/时间）不产生对 FOUND 的 MATCH 推荐
        String other = registerAndLogin();
        publishItem(other, mgr, "LOST", "蓝色雨伞", "操场丢伞一把", 4, 14L, LocalDateTime.now().minusDays(20));
        long noiseCnt = notifMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, loginUid(other))
                .eq(Notification::getBizType, NotifyType.MATCH));
        assertThat(noiseCnt).isZero();
    }

    @Test
    @DisplayName("发布/审核联动通知：作者收 ITEM_AUDIT 通过；驳回带理由；下架（非作者操作）通知")
    void auditNotifications() throws Exception {
        String stu = registerAndLogin();
        long uid = loginUid(stu);
        String mgr = login(MGR);
        long id = publishItem(stu, mgr, "LOST", "审核通知样本", "描述", 1, null, LocalDateTime.now());

        List<Notification> passed = notifMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, uid).eq(Notification::getBizType, NotifyType.ITEM_AUDIT));
        assertThat(passed).hasSize(1);
        assertThat(passed.get(0).getContent()).contains("已发布");

        // 治理下架（干事操作，非作者）→ 第二条 ITEM_AUDIT
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "2").param("reason", "信息不全")
                        .header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        List<Notification> after = notifMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, uid).eq(Notification::getBizType, NotifyType.ITEM_AUDIT));
        assertThat(after).hasSize(2);
        assertThat(after).anySatisfy(n -> assertThat(n.getContent()).contains("信息不全"));

        // 作者自助下架 → 不再骚扰自己（3 条不变）
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "2").param("reason", "自己收好了")
                        .header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0));
        long count = notifMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, uid).eq(Notification::getBizType, NotifyType.ITEM_AUDIT));
        assertThat(count).isEqualTo(2);
    }

    // ====================== 收件箱 ======================

    @Test
    @DisplayName("收件箱：分页/类型过滤/未读计数/单条与全部已读/越权静默")
    void inboxFlow() throws Exception {
        String stu = registerAndLogin();
        long uid = loginUid(stu);
        // 直接投递三条（service 口，绕开业务链噪音）
        for (int i = 1; i <= 3; i++) {
            jdbcTemplate.update("insert into notification (user_id,biz_type,biz_id,title,content,read_flag,created_at,deleted) values (?,?,?,?,?,?,?,0)",
                    uid, "REPLY", (long) i, "回复" + i, "内容" + i, 0, LocalDateTime.now());
        }
        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.count").value(3));
        mockMvc.perform(get("/api/notifications/mine").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.total").value(3));
        mockMvc.perform(get("/api/notifications/mine").param("bizType", "SYSTEM")
                        .header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.total").value(0));

        long nid = jdbcTemplate.queryForObject(
                "select id from notification where user_id=? and title='回复2'", Long.class, uid);
        mockMvc.perform(post("/api/notifications/" + nid + "/read").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.count").value(2));
        mockMvc.perform(get("/api/notifications/mine").param("onlyUnread", "true")
                        .header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.total").value(2));

        // 越权：B 标 A 的消息已读 → 静默 no-op，read_flag 不动
        String other = registerAndLogin();
        long nid3 = jdbcTemplate.queryForObject(
                "select id from notification where user_id=? and title='回复3'", Long.class, uid);
        mockMvc.perform(post("/api/notifications/" + nid3 + "/read").header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(jdbcTemplate.queryForObject(
                "select read_flag from notification where id=?", Integer.class, nid3)).isZero();

        // 全部已读
        mockMvc.perform(post("/api/notifications/read-all").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.updated").value(2));
        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.count").value(0));
        // 游客 401
        mockMvc.perform(get("/api/notifications/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("群发：notice:send 才有权限；全员=正常状态用户；空标题 400")
    void broadcast() throws Exception {
        String stu = registerAndLogin();
        mockMvc.perform(post("/api/admin/notifications/broadcast").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));

        String mgr = login(MGR);   // MANAGER 权限矩阵含 notice:send(8)
        int activeUsers = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where status=1 and deleted=0", Integer.class);
        MvcResult r = mockMvc.perform(post("/api/admin/notifications/broadcast").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"系统维护公告\",\"content\":\"今晚 23 点停机半小时\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        assertThat(envelope(r).path("data").path("sent").asInt()).isEqualTo(activeUsers);

        // 定向 userIds
        long uid = loginUid(stu);
        mockMvc.perform(post("/api/admin/notifications/broadcast").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"定向\",\"content\":\"只有你收到\",\"userIds\":[" + uid + "]}"))
                .andExpect(jsonPath("$.data.sent").value(1));
        mockMvc.perform(get("/api/notifications/mine").param("bizType", "SYSTEM")
                        .header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.total").value(2));   // 全员广播1 + 定向1

        mockMvc.perform(post("/api/admin/notifications/broadcast").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \",\"content\":\"c\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
    }

    @Test
    @DisplayName("消息不可删：DELETE 路由不存在（404/500 兜底皆非 2xx），行原样在；游客 401")
    void noDeleteChannel() throws Exception {
        String stu = registerAndLogin();
        // 游客打删除口：Security 先拦 401（写路径无登记=默认拒）
        mockMvc.perform(delete("/api/notifications/1")).andExpect(status().isUnauthorized());
        // 登录者打同一路径：路由未实装 → 非 2xx（NoHandler 兜底 500），且 notification 行必须原样
        int before = jdbcTemplate.queryForObject("select count(*) from notification", Integer.class);
        var del = mockMvc.perform(delete("/api/notifications/1").header("Authorization", "Bearer " + stu))
                .andReturn();
        assertThat(del.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(jdbcTemplate.queryForObject("select count(*) from notification", Integer.class))
                .isEqualTo(before);
        // service 口：userId=null 静默返回不炸（业务方 claim 路径大量可空目标）
        notificationService.push(null, NotifyType.SYSTEM, null, "t", "c");   // 不抛异常即通过
        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.count").value(0));
    }
}

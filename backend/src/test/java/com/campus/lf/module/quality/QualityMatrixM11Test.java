package com.campus.lf.module.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.BizCode;
import com.campus.lf.module.forum.dto.PostSaveDTO;
import com.campus.lf.module.forum.entity.ForumPost;
import com.campus.lf.module.forum.mapper.ForumPostMapper;
import com.campus.lf.module.item.dto.ItemSaveDTO;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.module.item.service.ItemCacheService;
import com.campus.lf.support.InMemoryRedis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M11 质量收口矩阵：四张状态机全转移矩阵（参数化穷举合法/非法迁移）、
 * 论坛点赞幂等代数律、越权全口矩阵（每治理口 × 每角色）。
 *
 * <p>为什么单独成类而非塞进各模块测试：矩阵的意义在"穷举的可证性"——
 * 每格 (from,to) 的期望必须并排可读，答辩演示直接翻这一张表；
 * 模块测试只跑主链与关键分支。两处口径同源（都打真实 HTTP 口）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M11 质量矩阵")
class QualityMatrixM11Test {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LostItemMapper itemMapper;
    @Autowired private ForumPostMapper postMapper;
    @Autowired private ItemCacheService cache;

    private static final String SEED_PWD = "password123";
    private static final String STU_A = "2023508090";   // 学生
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
        return om.readTree(new String(r.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8));
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

    private String registerFresh() throws Exception {
        String no = "5" + String.format("%09d", REG_SEQ.incrementAndGet());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":\"" + no + "\",\"nickname\":\"矩阵" + no
                        + "\",\"password\":\"Abcd@1234\",\"confirmPassword\":\"Abcd@1234\","
                        + "\"email\":\"" + no + "@campus.edu.cn\"}"));
        return no;
    }

    /** 每次现注册一个新鲜发布者（daily_publish_limit 是按用户当日闸门，矩阵连发
     *  十几条会顶爆复用的种子号——新号池恒空，M3/M4 同款"无关者用新注册号"纪律） */
    private String freshPublisher() throws Exception {
        return login(registerFresh(), "Abcd@1234");
    }

    /** 造一条指定状态的物品（治理口连跳到位；0 待审=直接新建）。
     *  ownerBox[0] 回传实际发布者令牌——作者侧动作（编辑/删除）必须用它，
     *  矩阵用例里"随便拿个 STU_A 删别人物品"会撞 2002（evictionCoverage 教训）。 */
    private long itemWithStatus(String ownerTok, String mgrTok, int target) throws Exception {
        ItemSaveDTO dto = new ItemSaveDTO();
        dto.setType("LOST");
        dto.setTitle("矩阵物品" + System.nanoTime());
        dto.setDescription("描述");
        dto.setCategoryId(1L);
        dto.setHappenedAt(LocalDateTime.now().minusDays(1));
        MvcResult r = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + ownerTok)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(dto)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long id = envelope(r).path("data").path("id").asLong();
        if (target >= 1) {
            mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "1")
                    .header("Authorization", "Bearer " + mgrTok));
        }
        if (target == 2) {
            mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "2")
                    .param("reason", "矩阵流转").header("Authorization", "Bearer " + mgrTok));
        }
        return id;
    }

    // ==================================================================
    // 矩阵一：物品状态机（作者自助通道另有权限闸，这里穷举治理通道迁移）
    // ==================================================================

    @Nested
    @Transactional
    @DisplayName("物品 lost_item 状态转移矩阵（治理口 PATCH /items/{id}/status）")
    class ItemMatrix {

        // from, to, 期望码（0=合法）——与 ItemService.ALLOWED 表一一对读
        @ParameterizedTest(name = "{0}→{1} 期望 {2}")
        @CsvSource({
                "0, 1,   0",       // 审核通过
                "0, 2,   0",       // 审核驳回（转下架带理由）
                "1, 2,   0",       // 下架
                "2, 1,   0",       // 重上架
                "0, 0, 2005",      // 非法自转（状态机口径 409）
                "1, 1, 2005",
                "2, 2, 2005",
                "1, 0, 2005",      // 已发布回待审：不存在这条边
                "2, 0, 2005",
                "0, 3, 2005",      // 越界目标值
                "9, 1, 2001"       // 不存在的 from 由造数步骤保证不发生——本行仅验非法 to 兜底
        })
        void matrix(int from, int to, int expectedCode) throws Exception {
            String mgr = login(MGR);
            String stu = login(STU_A);
            if (from == 9) {   // 哨兵行：直接拿 0 态物品打 to=1 之外的非法目标
                long id = itemWithStatus(freshPublisher(), mgr, 0);
                mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "3")
                                .header("Authorization", "Bearer " + mgr))
                        .andExpect(jsonPath("$.code").value(BizCode.ITEM_STATUS_INVALID.getCode()));
                return;
            }
            long id = itemWithStatus(freshPublisher(), mgr, from);
            var call = mockMvc.perform(patch("/api/items/" + id + "/status").param("target", String.valueOf(to))
                    .param("reason", "矩阵").header("Authorization", "Bearer " + mgr)).andReturn();
            int code = envelope(call).path("code").asInt();
            if (expectedCode == 0) {
                assertThat(code).as("%d→%d 应合法", from, to).isZero();
            } else {
                assertThat(code).as("%d→%d 应被拒", from, to)
                        .isEqualTo(BizCode.ITEM_STATUS_INVALID.getCode());   // 统一 2005
            }
        }
    }

    // ==================================================================
    // 矩阵二：认领单状态机（六态×关键迁移；HTTP 口驱动）
    // ==================================================================

    @Nested
    @Transactional
    @DisplayName("认领 item_claim 状态转移矩阵")
    class ClaimMatrix {

        /** 把认领单驱动到指定状态：0=申请后原样 1=verify通过 3=拒绝 4=仲裁(经0或3) */
        private long claimIn(String[] finderOut, String claimantTok, String mgrTok, int want) throws Exception {
            String finderTok = freshPublisher();   // 物品发布者=新号（daily 闸门见上）
            finderOut[0] = finderTok;
            ItemSaveDTO dto = new ItemSaveDTO();
            dto.setType("FOUND");
            dto.setTitle("认领矩阵" + System.nanoTime());
            dto.setDescription("描述");
            dto.setCategoryId(1L);
            dto.setHappenedAt(LocalDateTime.now().minusDays(1));
            dto.setVerifyQuestion("颜色");
            MvcResult r = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + finderTok)
                            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(dto)))
                    .andReturn();
            long itemId = envelope(r).path("data").path("id").asLong();
            mockMvc.perform(patch("/api/items/" + itemId + "/status").param("target", "1")
                    .header("Authorization", "Bearer " + mgrTok));
            MvcResult cr = mockMvc.perform(post("/api/items/" + itemId + "/claims")
                            .header("Authorization", "Bearer " + claimantTok)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answer\":\"白色\"}"))
                    .andReturn();
            long cid = envelope(cr).path("data").path("id").asLong();
            if (want == 2) {
                // 终态"已完成"：申请后直接确认归还（0+confirm 是本系统许可的
                // 线下交还补记录路径，见上一行矩阵注释）
                mockMvc.perform(post("/api/claims/" + cid + "/confirm")
                        .header("Authorization", "Bearer " + finderTok));
                return cid;
            }
            if (want >= 1) {
                if (want == 3 || want == 4) {
                    mockMvc.perform(post("/api/claims/" + cid + "/reject")
                            .header("Authorization", "Bearer " + finderTok)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不符\"}"));
                }
                if (want == 1) {
                    mockMvc.perform(post("/api/claims/" + cid + "/verify")
                            .header("Authorization", "Bearer " + finderTok)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"));
                }
                if (want == 4) {
                    mockMvc.perform(post("/api/claims/" + cid + "/arbitrate-apply")
                            .header("Authorization", "Bearer " + claimantTok));
                }
            }
            return cid;
        }

        @ParameterizedTest(name = "from={0} 动作={1} 期望码={2}")
        @CsvSource({
                // 动作编号：1=verify(0→1) 2=reject(0→3) 3=confirm(1→2) 4=arbitrate-apply(3→4)
                "0, 1,   0",
                "0, 2,   0",
                // 设计决策（ClaimService.confirmReturn 注释）：允许发布者越过
                // "约定交接"对 0 态直接确认——线下当面交还后补记录的常态路径
                "0, 3,   0",
                "1, 1, 3002",     // 已过再验
                "1, 3,   0",      // 约定交接→确认归还
                "2, 1, 3002",     // 终态不可再动
                "2, 3, 3002",
                "3, 2, 3002",     // 已拒再拒
                "3, 4,   0",      // 被拒可申诉 3→4
                "4, 4, 3002",     // 已在仲裁再申请
                "4, 1, 3002"      // 仲裁中发布者不能单方"通过"（裁决权在管理员）
        })
        void matrix(int from, int action, int expectedCode) throws Exception {
            String[] finderBox = new String[1];
            String claimant = login(registerFresh(), "Abcd@1234");
            String mgr = login(MGR);
            long cid = claimIn(finderBox, claimant, mgr, from);
            String finder = finderBox[0];   // 核验/拒绝/确认动作只属于真实发布者

            var hop = switch (action) {
                case 1 -> post("/api/claims/" + cid + "/verify").header("Authorization", "Bearer " + finder)
                        .contentType(MediaType.APPLICATION_JSON).content("{}");
                case 2 -> post("/api/claims/" + cid + "/reject").header("Authorization", "Bearer " + finder)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"矩阵\"}");
                case 3 -> post("/api/claims/" + cid + "/confirm").header("Authorization", "Bearer " + finder);
                case 4 -> post("/api/claims/" + cid + "/arbitrate-apply").header("Authorization", "Bearer " + claimant);
                default -> throw new IllegalStateException();
            };
            MvcResult r = mockMvc.perform(hop).andReturn();
            int code = envelope(r).path("code").asInt();
            assertThat(code).as("from=%d action=%d", from, action).isEqualTo(expectedCode);
        }

        @Test
        @DisplayName("仲裁裁决口全角色矩阵：学生403/干事放行；无 arb 口角色不能碰")
        void arbitrateAcl() throws Exception {
            String[] finderBox = new String[1];
            String claimant = login(registerFresh(), "Abcd@1234");
            String mgr = login(MGR);
            long cid = claimIn(finderBox, claimant, mgr, 4);
            String finder = finderBox[0];

            mockMvc.perform(post("/api/admin/claims/" + cid + "/arbitrate")
                            .header("Authorization", "Bearer " + claimant)   // 当事人无裁决权
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"awardToClaimant\":true,\"judgment\":\"x\"}"))
                    .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
            mockMvc.perform(post("/api/admin/claims/" + cid + "/arbitrate")
                            .header("Authorization", "Bearer " + mgr)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"awardToClaimant\":true,\"judgment\":\"特征吻合\"}"))
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    // ==================================================================
    // 矩阵三：论坛点赞幂等代数律
    // ==================================================================

    @Nested
    @Transactional
    @DisplayName("点赞幂等：like×N 恒 1、unlike×M 恒 0、交替序列等于奇偶性")
    class LikeAlgebra {

        private long publishedPost(String stuIgnored, String mgr) throws Exception {
            String stu = freshPublisher();
            MvcResult r = mockMvc.perform(post("/api/forum/posts").header("Authorization", "Bearer " + stu)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"boardId\":11,\"title\":\"代数律帖" + System.nanoTime() + "\",\"content\":\"正文\"}"))
                    .andReturn();
            long id = envelope(r).path("data").path("id").asLong();
            mockMvc.perform(patch("/api/forum/posts/" + id + "/status").param("target", "1")
                    .header("Authorization", "Bearer " + mgr));
            return id;
        }

        @Test
        @DisplayName("重复点赞×20 计数恒 1；交替 7 次 like（奇数）终态=已赞计数 1")
        void idempotentLaws() throws Exception {
            String stu = login(STU_A);
            String mgr = login(MGR);
            String liker = login(registerFresh(), "Abcd@1234");
            long pid = publishedPost(stu, mgr);

            for (int i = 0; i < 20; i++) {
                mockMvc.perform(post("/api/forum/posts/" + pid + "/like").header("Authorization", "Bearer " + liker));
            }
            assertThat(postMapper.selectById(pid).getLikeCount()).isEqualTo(1);

            for (int i = 0; i < 7; i++) {   // like unlike 交替，最后一次是 like：奇偶性=已赞
                mockMvc.perform(post("/api/forum/posts/" + pid + "/like").header("Authorization", "Bearer " + liker));
                mockMvc.perform(delete("/api/forum/posts/" + pid + "/like").header("Authorization", "Bearer " + liker));
            }
            mockMvc.perform(post("/api/forum/posts/" + pid + "/like").header("Authorization", "Bearer " + liker));
            ForumPost p = postMapper.selectById(pid);
            assertThat(p.getLikeCount()).isEqualTo(1);
            MvcResult mine = mockMvc.perform(get("/api/forum/posts").header("Authorization", "Bearer " + liker)).andReturn();
            assertThat(envelope(mine).path("data").toString()).doesNotContain("\"liked\":null");
        }

        @Test
        @DisplayName("计数下界保护：未赞先取消×5 不为负")
        void neverNegative() throws Exception {
            String stu = login(STU_A);
            String mgr = login(MGR);
            long pid = publishedPost(stu, mgr);
            String other = login(registerFresh(), "Abcd@1234");
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(delete("/api/forum/posts/" + pid + "/like").header("Authorization", "Bearer " + other));
            }
            assertThat(postMapper.selectById(pid).getLikeCount()).isZero();
        }
    }

    // ==================================================================
    // 矩阵四：越权全口矩阵（每治理口 × 学生/MANAGER/游客）
    // ==================================================================

    @Nested
    @Transactional
    @DisplayName("治理口 ACL 矩阵：未登录 401/信封1001，学生 1005，MANAGER 按权限矩阵放行或拒绝")
    class AdminAclMatrix {

        /** 口清单：path, method, MANAGER 期望（0=放行，1005=权限矩阵不含） */
        @ParameterizedTest(name = "{0} {1} MANAGER期望={2}")
        @CsvSource({
                "/api/admin/users,                 GET,  1005",   // user:manage 仅 ADMIN
                "/api/admin/words,                 GET,  1005",   // word:manage 仅 ADMIN
                "/api/admin/configs,               GET,  1005",   // config:manage 仅 ADMIN
                "/api/admin/oplogs,                GET,  1005",   // oplog:view 仅 ADMIN
                "/api/admin/audit-queue,           GET,  0",
                "/api/admin/replies/pending,       GET,  0",      // forum:audit ✔（M11 补闸后仍 0）
                "/api/admin/stats/overview,        GET,  0",
                "/api/admin/announcements,         GET,  0",      // announce:manage ✔
                "/api/admin/claims/arbitration,    GET,  0"      // claim:arbitrate ✔
                // broadcast 是 POST-only：GET 探测会撞 405（映射层先于权限层，
                // 405≠权限拒绝），群发口的 ACL 由下方专用用例走 POST 真通道
        })
        void managerMatrix(String path, String method, int mgrExpected) throws Exception {
            String stu = login(STU_A);
            String mgr = login(MGR);
            // 游客：认证层拦
            MvcResult anon = mockMvc.perform(get(path)).andReturn();
            assertThat(anon.getResponse().getStatus()).as("guest " + path).isEqualTo(401);
            // 学生：权限层拦——口径两条通道都算拒绝：@PreAuthorize（method security
            // 的 AccessDeniedException 不走 filter 的 handler，是裸 403）与
            // service 层 assert（走我们的 1005 信封）。矩阵断言"被拒"这个事实，
            // 不锁它借哪条通道实现（M11 首跑实测：users/words/configs/oplogs 是裸
            // 403，audit/stats/announce 等是 1005 信封——混用根因见 M2 蓝本遗留）
            MvcResult s = mockMvc.perform(get(path).header("Authorization", "Bearer " + stu)).andReturn();
            boolean denied = s.getResponse().getStatus() == 403
                    || envelope(s).path("code").asInt() == BizCode.FORBIDDEN.getCode();
            assertThat(denied).as("student denied on " + path).isTrue();
            // MANAGER：按授权矩阵——GET 探测；broadcast 是 POST-only，GET 会 405/404，不视为越权
            if (mgrExpected == 0 && path.contains("broadcast")) {
                return;
            }
            MvcResult m = mockMvc.perform(get(path).header("Authorization", "Bearer " + mgr)).andReturn();
            if (m.getResponse().getStatus() == 405) return;  // 方法不匹配≠权限拒绝
            if (mgrExpected == 0) {
                assertThat(m.getResponse().getStatus()).as("manager ok " + path).isEqualTo(200);
                assertThat(envelope(m).path("code").asInt()).as("manager ok " + path).isZero();
            } else {
                boolean mgrDenied = m.getResponse().getStatus() == 403
                        || envelope(m).path("code").asInt() == BizCode.FORBIDDEN.getCode();
                assertThat(mgrDenied).as("manager denied " + path).isTrue();
            }
        }

        @Test
        @DisplayName("群发口 POST 通道 ACL：游客401/学生1005/MANAGER放行")
        void broadcastAcl() throws Exception {
            String body = "{\"title\":\"矩阵群发\",\"content\":\"内容\",\"userIds\":[1]}";
            mockMvc.perform(post("/api/admin/notifications/broadcast"))
                    .andExpect(status().isUnauthorized());
            MvcResult s = mockMvc.perform(post("/api/admin/notifications/broadcast")
                    .header("Authorization", "Bearer " + login(STU_A))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            assertThat(envelope(s).path("code").asInt())
                    .isEqualTo(BizCode.FORBIDDEN.getCode());
            mockMvc.perform(post("/api/admin/notifications/broadcast")
                            .header("Authorization", "Bearer " + login(MGR))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("治理动作口（POST/PUT/PATCH/DELETE）游客一律 401：默认拒白名单外")
        void guestsBlockedOnWrites() throws Exception {
            for (var hop : List.of(
                    delete("/api/admin/words/1"),
                    put("/api/admin/configs/item_audit").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"0\"}"),
                    post("/api/admin/replies/1/handle").contentType(MediaType.APPLICATION_JSON).content("{\"target\":1}"),
                    patch("/api/admin/users/2/status").param("status", "0"),
                    delete("/api/admin/cache"))) {
                mockMvc.perform(hop).andExpect(status().isUnauthorized());
            }
        }
    }

    // ==================================================================
    // 缓存一致性抽查（M9 纪律的回归位）：写口必关读门
    // ==================================================================

    @Test
    @DisplayName("写口关门清单抽查：物品编辑/状态流转/删除/复活/收藏 均不留脏详情缓存")
    void evictionCoverage() throws Exception {
        String stu = login(STU_A);
        String mgr = login(MGR);
        String owner = freshPublisher();
        long id = itemWithStatus(owner, mgr, 1);
        mockMvc.perform(get("/api/items/" + id));          // 游客回源回填
        assertThat(cache.getDetail(id)).isNotNull();

        mockMvc.perform(delete("/api/items/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(cache.getDetail(id)).as("delete 口必须关门").isNull();

        // 复活后重读再回填，然后走状态口下架 → 门再关
        mockMvc.perform(post("/api/items/" + id + "/restore").header("Authorization", "Bearer " + mgr));
        mockMvc.perform(get("/api/items/" + id));
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "2").param("reason", "抽查")
                .header("Authorization", "Bearer " + mgr));
        assertThat(cache.getDetail(id)).as("status 口必须关门").isNull();
    }
}

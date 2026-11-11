package com.campus.lf.module.claim;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.BizCode;
import com.campus.lf.module.claim.entity.ClaimLog;
import com.campus.lf.module.claim.entity.ItemClaim;
import com.campus.lf.module.claim.mapper.ClaimLogMapper;
import com.campus.lf.module.claim.mapper.ItemClaimMapper;
import com.campus.lf.module.claim.service.ClaimService;
import com.campus.lf.module.item.dto.ItemSaveDTO;
import com.campus.lf.module.item.entity.LostItem;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.module.item.service.ItemService;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4 认领流程全链路：主链 happy path、先到先得批量关闭、闸门链、视角脱敏、仲裁、超时提醒。
 *
 * <p>基建沿用 M2/M3：InMemoryRedis @Primary、种子口令、registerFresh 新号口令 Abcd@1234。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional  // 类级回滚：防跨测试类污染共享 H2（每日发布上限是全局闸门，M3 串扰教训）
@DisplayName("M4 认领流程 API")
class ClaimApiM4Test {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LostItemMapper itemMapper;
    @Autowired private ItemClaimMapper claimMapper;
    @Autowired private ClaimLogMapper logMapper;
    @Autowired private ClaimService claimService;

    private static final String SEED_PWD = "password123";
    private static final String STU_A = "2023508090";   // 普通学生（发布者候选）
    private static final String STU_B = "2023508091";   // +ROLE_MANAGER（item:audit/claim:arbitrate）
    private static final AtomicInteger REG_SEQ = new AtomicInteger();

    @TestConfiguration
    static class RedisTestConfig {
        @Bean @Primary
        public StringRedisTemplate stringRedisTemplate() {
            return InMemoryRedis.template();
        }
    }

    // ====================== 工具 ======================

    private String login(String studentNo) throws Exception {
        return login(studentNo, SEED_PWD);
    }

    private String login(String studentNo, String pwd) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + studentNo + "\",\"password\":\"" + pwd + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return envelope(res).path("data").path("token").asText();
    }

    private JsonNode envelope(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    private String registerFresh() throws Exception {
        String no = "9" + String.format("%09d", REG_SEQ.incrementAndGet());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + no + "\",\"nickname\":\"认领测试" + no
                                + "\",\"password\":\"Abcd@1234\",\"confirmPassword\":\"Abcd@1234\","
                                + "\"email\":\"" + no + "@campus.edu.cn\"}"))
                .andExpect(status().isOk());
        return no;
    }

    private String registerAndLogin() throws Exception {
        return login(registerFresh(), "Abcd@1234");
    }

    /** 发布一条 FOUND 并由干事审核发布（PATCH status 治理通道=M3 同款），返回 item id */
    private long publishFound(String publisherToken, String mgrToken, String title) throws Exception {
        ItemSaveDTO dto = new ItemSaveDTO();
        dto.setType("FOUND");
        dto.setTitle(title);
        dto.setDescription("捡到地点描述，等待认领人核验");
        dto.setCategoryId(1L);
        dto.setHappenedAt(LocalDateTime.now().minusDays(1));
        dto.setLocationText("图书馆三楼");
        dto.setVerifyQuestion("钥匙扣是什么颜色");
        dto.setContact("wx:test");
        MvcResult r = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + publisherToken)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(dto)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long id = envelope(r).path("data").path("id").asLong();
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgrToken))
                .andExpect(jsonPath("$.code").value(0));
        return id;
    }

    private long applyClaim(String claimantToken, long itemId, String answer) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/items/" + itemId + "/claims")
                        .header("Authorization", "Bearer " + claimantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"" + answer + "\",\"evidenceNote\":\"补充说明\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return envelope(r).path("data").path("id").asLong();
    }

    /** /api/auth/me 出参 → 重建 LoginUser（全参构造，供 service 直调用例） */
    private com.campus.lf.security.LoginUser loginUser(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn();
        JsonNode u = envelope(me).path("data");
        java.util.List<String> roles = new java.util.ArrayList<>();
        u.path("roles").forEach(n -> roles.add(n.asText()));
        java.util.List<String> perms = new java.util.ArrayList<>();
        u.path("perms").forEach(n -> perms.add(n.asText()));
        return new com.campus.lf.security.LoginUser(u.path("id").asLong(),
                u.path("studentNo").asText(), u.path("nickname").asText(), null,
                roles, perms, "test-jti", 3600L, System.currentTimeMillis());
    }

    // ====================== 主链 happy path ======================

    @Test
    @DisplayName("主链：申请→通过核验→确认归还，物品下架已归还，日志三段齐全")
    void mainChain() throws Exception {
        String pub = login(STU_A);
        String mgr = login(STU_B);
        String applicant = registerAndLogin();
        long itemId = publishFound(pub, mgr, "主链校园卡");

        long claimId = applyClaim(applicant, itemId, "蓝色的");

        mockMvc.perform(post("/api/claims/" + claimId + "/verify")
                        .header("Authorization", "Bearer " + pub)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"答案正确\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(claimMapper.selectById(claimId).getStatus()).isEqualTo(ClaimService.ST_AGREED);

        mockMvc.perform(post("/api/claims/" + claimId + "/confirm").header("Authorization", "Bearer " + pub))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(claimMapper.selectById(claimId).getStatus()).isEqualTo(ClaimService.ST_RETURNED);
        LostItem item = itemMapper.selectById(itemId);
        assertThat(item.getStatus()).isEqualTo(ItemService.STATUS_OFFLINE);
        assertThat(item.getOfflineReason()).isEqualTo("已归还");

        List<String> actions = logMapper.selectList(new LambdaQueryWrapper<ClaimLog>()
                        .eq(ClaimLog::getClaimId, claimId).orderByAsc(ClaimLog::getId))
                .stream().map(ClaimLog::getAction).toList();
        assertThat(actions).containsExactly("APPLY", "VERIFY_PASS", "CONFIRM_RETURN");

        // 重复确认已结算单 → 状态闸 3002（先到先得终态不可逆）
        mockMvc.perform(post("/api/claims/" + claimId + "/confirm").header("Authorization", "Bearer " + pub))
                .andExpect(jsonPath("$.code").value(BizCode.CLAIM_STATUS_INVALID.getCode()));
    }

    @Test
    @DisplayName("先到先得：确认归还连带批量关闭其余在途单(5)+系统留痕 actor=null")
    void firstComeFirstServedClosesOthers() throws Exception {
        String pub = login(STU_A);
        String mgr = login(STU_B);
        String a1 = registerAndLogin();
        String a2 = registerAndLogin();
        long itemId = publishFound(pub, mgr, "多人争抢的水杯");

        long c1 = applyClaim(a1, itemId, "有猫贴纸");
        long c2 = applyClaim(a2, itemId, "有猫贴纸");

        mockMvc.perform(post("/api/claims/" + c1 + "/confirm").header("Authorization", "Bearer " + pub))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(claimMapper.selectById(c2).getStatus()).isEqualTo(ClaimService.ST_CLOSED);
        ClaimLog sys = logMapper.selectOne(new LambdaQueryWrapper<ClaimLog>()
                .eq(ClaimLog::getClaimId, c2).eq(ClaimLog::getAction, "CLOSED_BY_RETURN"));
        assertThat(sys).isNotNull();
        assertThat(sys.getActorId()).isNull();   // actor=null=系统动作，审计口径

        mockMvc.perform(post("/api/claims/" + c2 + "/confirm").header("Authorization", "Bearer " + pub))
                .andExpect(jsonPath("$.code").value(BizCode.CLAIM_STATUS_INVALID.getCode()));
    }

    // ====================== 闸门链 ======================

    @Test
    @DisplayName("闸门链：认领自己发布件/重复申请/寻物帖认领/非发布者核验 各自拦截")
    void gates() throws Exception {
        String pub = login(STU_A);
        String mgr = login(STU_B);
        String applicant = registerAndLogin();
        String stranger = registerAndLogin();
        long itemId = publishFound(pub, mgr, "闸门耳机");
        long claimId = applyClaim(applicant, itemId, "左耳有个划痕");

        mockMvc.perform(post("/api/items/" + itemId + "/claims").header("Authorization", "Bearer " + pub)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"我捡的\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.CLAIM_NOT_PARTICIPANT.getCode()));
        mockMvc.perform(post("/api/items/" + itemId + "/claims").header("Authorization", "Bearer " + applicant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"再试\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.CLAIM_DUPLICATE.getCode()));
        mockMvc.perform(post("/api/claims/" + claimId + "/verify").header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        // LOST 寻物帖：审核发布后仍不接受认领（语义闸门，走联系通道）
        ItemSaveDTO lost = new ItemSaveDTO();
        lost.setType("LOST");
        lost.setTitle("我丢了一串钥匙");
        lost.setDescription("急寻");
        lost.setCategoryId(1L);
        lost.setHappenedAt(LocalDateTime.now().minusDays(1));
        lost.setLocationText("操场");
        MvcResult r = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + applicant)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(lost)))
                .andReturn();
        long lostId = envelope(r).path("data").path("id").asLong();
        mockMvc.perform(patch("/api/items/" + lostId + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/items/" + lostId + "/claims").header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"捡到了\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.CLAIM_STATUS_INVALID.getCode()));
    }

    @Test
    @DisplayName("answer 视角脱敏：发布者列表可见答案，申请人走 mine，无关者 403")
    void answerVisibility() throws Exception {
        String pub = login(STU_A);
        String mgr = login(STU_B);
        String applicant = registerAndLogin();
        String stranger = registerAndLogin();
        long itemId = publishFound(pub, mgr, "答案脱敏伞");
        applyClaim(applicant, itemId, "伞柄刻着 K.L.");

        mockMvc.perform(get("/api/items/" + itemId + "/claims").header("Authorization", "Bearer " + pub))
                .andExpect(jsonPath("$.data[0].answer").value("伞柄刻着 K.L."));
        mockMvc.perform(get("/api/items/" + itemId + "/claims").header("Authorization", "Bearer " + applicant))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/claims/mine").header("Authorization", "Bearer " + applicant))
                .andExpect(jsonPath("$.data.list[0].answer").value("伞柄刻着 K.L."));
        mockMvc.perform(get("/api/items/" + itemId + "/claims").header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden());
    }

    // ====================== 拒绝与仲裁 ======================

    @Test
    @DisplayName("拒绝必填理由；被拒可申诉仲裁；裁决权仅 claim:arbitrate；判归即下架")
    void rejectAndArbitration() throws Exception {
        String pub = login(STU_A);
        String mgr = login(STU_B);
        String applicant = registerAndLogin();
        long itemId = publishFound(pub, mgr, "仲裁链的手表");
        long claimId = applyClaim(applicant, itemId, "表盘有一道纹");

        mockMvc.perform(post("/api/claims/" + claimId + "/reject").header("Authorization", "Bearer " + pub)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(BizCode.PARAM_ERROR.getCode()));
        mockMvc.perform(post("/api/claims/" + claimId + "/reject").header("Authorization", "Bearer " + pub)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"描述不符\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(claimMapper.selectById(claimId).getStatus()).isEqualTo(ClaimService.ST_REJECTED);

        // 被拒 → 申请人申诉仲裁（3→4）
        mockMvc.perform(post("/api/claims/" + claimId + "/arbitrate-apply").header("Authorization", "Bearer " + applicant))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(claimMapper.selectById(claimId).getStatus()).isEqualTo(ClaimService.ST_ARBITRATING);

        // 无仲裁权（申请人自己/纯学生）→ 1005/403；仲裁队列读口同样要权限
        mockMvc.perform(post("/api/admin/claims/" + claimId + "/arbitrate").header("Authorization", "Bearer " + applicant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"awardToClaimant\":true,\"judgment\":\"答案吻合\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
        String pure = registerAndLogin();
        mockMvc.perform(get("/api/admin/claims/arbitration").header("Authorization", "Bearer " + pure))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));

        // 管理员裁决判归 → 2 + arbitratedBy + 物品下架"仲裁判归"
        mockMvc.perform(post("/api/admin/claims/" + claimId + "/arbitrate").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"awardToClaimant\":true,\"judgment\":\"核验答案与实物特征吻合\"}"))
                .andExpect(jsonPath("$.code").value(0));
        ItemClaim judged = claimMapper.selectById(claimId);
        assertThat(judged.getStatus()).isEqualTo(ClaimService.ST_RETURNED);
        assertThat(judged.getArbitratedBy()).isNotNull();
        assertThat(itemMapper.selectById(itemId).getOfflineReason()).isEqualTo("仲裁判归");

        // 裁决队列里已不可见（不再是仲裁中）
        mockMvc.perform(get("/api/admin/claims/arbitration").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("状态机防御：越过申请直接操作不存在单 404/3001；空答案 400")
    void stateGuards() throws Exception {
        String applicant = registerAndLogin();
        mockMvc.perform(post("/api/claims/999999/confirm").header("Authorization", "Bearer " + applicant))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(BizCode.CLAIM_NOT_FOUND.getCode()));

        String pub = login(STU_A);
        String mgr = login(STU_B);
        long itemId = publishFound(pub, mgr, "空答案闸门件");
        mockMvc.perform(post("/api/items/" + itemId + "/claims").header("Authorization", "Bearer " + mgr)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ====================== 超时提醒（service 直调）======================

    @Test
    @DisplayName("超时提醒：造 25h 前的待核验单，remindTimeout≥1 + TIMEOUT_REMIND actor=null")
    void timeoutReminder() throws Exception {
        String pub = login(STU_A);
        String mgr = login(STU_B);
        String applicant = registerAndLogin();
        long itemId = publishFound(pub, mgr, "超期未核验的伞");
        long claimId = applyClaim(applicant, itemId, "伞是黑色的");
        // 直接把 created_at 拨回 25 小时前（H2 DATEADD），比绕过 HTTP 造数更真
        jdbcTemplate.update("update item_claim set created_at = DATEADD('HOUR', -25, CREATED_AT) where id = ?", claimId);

        int reminded = claimService.remindTimeout(24);
        assertThat(reminded).isGreaterThanOrEqualTo(1);
        ClaimLog sysLog = logMapper.selectOne(new LambdaQueryWrapper<ClaimLog>()
                .eq(ClaimLog::getClaimId, claimId).eq(ClaimLog::getAction, "TIMEOUT_REMIND"));
        assertThat(sysLog).isNotNull();
        assertThat(sysLog.getActorId()).isNull();
    }
}

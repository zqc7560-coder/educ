package com.campus.lf.module.item;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.BizCode;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 物品模块接口测试 —— 里程碑验收（《进度.md》M3 行）：
 * 发布/检索/详情/编辑/状态机矩阵/乐观锁/可见性矩阵/闸门 全链路。
 *
 * <p>基建沿用 M2：InMemoryRedis @Primary（JwtAuthFilter 的吊销双闸需要读写语义）、
 * 类级 @Transactional、信封 UTF-8 显式解码。
 *
 * <p>计数断言纪律（M2 收口时立的规矩）：涉及"总数/条数"的断言一律带 id 段或
 * 关键词过滤，绝不裸 count 共享库——同 JVM 其它测试类的落库样本会串进来。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M3 物品: 发布/检索/状态机/乐观锁/可见性/闸门")
class ItemApiM3Test {

    private static final String SEED_PWD = "password123";
    private static final String STU_A = "2023508090";
    private static final String STU_B = "2023508091";   // +ROLE_MANAGER（item:audit/item:offline）

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LostItemMapper itemMapper;

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

    private JsonNode envelope(MvcResult res) throws Exception {
        return objectMapper.readTree(
                new String(res.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    private String registerFresh() throws Exception {
        String no = "m3u" + (System.nanoTime() % 1_000_000_000L);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentNo", no, "password", "Abcd@1234",
                                "nickname", "M3测试", "email", no + "@t.campus.edu.cn"))))
                .andExpect(jsonPath("$.code").value(0));
        return no;
    }

    /** 注册新鲜账号并直接登录（新注册口令固定 Abcd@1234——SEED_PWD 只属于种子号） */
    private String registerAndLogin() throws Exception {
        String no = registerFresh();
        return login(no, "Abcd@1234");
    }

    /** 发布一条合法 FOUND（默认带核验问题；返回 data.id） */
    private long createItem(String token, String type, String title, String verifyQ) throws Exception {
        ItemSaveDTO dto = new ItemSaveDTO();
        dto.setType(type);
        dto.setTitle(title);
        dto.setDescription("在" + title + "附近捡到，请描述特征认领");
        dto.setCategoryId(1L);
        dto.setCampusId(1L);
        dto.setBuildingId(11L);
        dto.setLocationText("一号楼东门");
        dto.setHappenedAt(LocalDateTime.now().minusDays(1));
        dto.setContact("13800000000");
        dto.setVerifyQuestion(verifyQ);
        MvcResult res = mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = envelope(res);
        assertThat(body.path("code").asInt()).isZero();
        return body.path("data").path("id").asLong();
    }

    /** 把物品推到已发布态（走治理流转，模拟审核通过） */
    private void publishViaAudit(String managerToken, long id) throws Exception {
        mockMvc.perform(patch("/api/items/" + id + "/status")
                        .param("target", "1")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ====================== 发布与初始态 ======================

    @Test
    @DisplayName("发布默认进待审(开关=1)：公开列表不可见，mine 对自己透明")
    void createEntersPendingAudit() throws Exception {
        String stu = login(STU_A);
        long id = createItem(stu, "FOUND", "校园卡一张", "卡上的姓名是？");

        LostItem db = itemMapper.selectById(id);
        assertThat(db.getStatus()).isEqualTo(ItemService.STATUS_PENDING);

        // 匿名公开列表不含它（按标题 LIKE 精确圈定，不裸 count）
        MvcResult list = mockMvc.perform(get("/api/items").param("keyword", "校园卡一张"))
                .andExpect(status().isOk()).andReturn();
        assertThat(envelope(list).path("data").path("total").asLong()).isZero();

        // mine=1 对自己可见
        MvcResult mine = mockMvc.perform(get("/api/items").param("mine", "true")
                        .header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        boolean found = false;
        for (JsonNode n : envelope(mine).path("data").path("list")) {
            if (n.path("id").asLong() == id) found = true;
        }
        assertThat(found).as("待审物品对作者可见").isTrue();

        // 匿名带 mine=1 显式 1001（白名单路径的特判——博客 M3 同款坑在此复钉）
        mockMvc.perform(get("/api/items").param("mine", "true"))
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("FOUND 缺核验问题 2003；LOST 不需要")
    void foundRequiresVerifyQuestion() throws Exception {
        String stu = login(STU_A);
        ItemSaveDTO dto = new ItemSaveDTO();
        dto.setType("FOUND");
        dto.setTitle("没核验问题的拾物");
        dto.setDescription("描述文本够长才能过校验");
        dto.setCategoryId(2L);
        dto.setHappenedAt(LocalDateTime.now().minusHours(2));
        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(jsonPath("$.code").value(BizCode.VERIFY_QUESTION_REQUIRED.getCode()));

        dto.setType("LOST");
        dto.setTitle("丢了钱包");
        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("入口校验：未来时间/脏分类/楼栋校区不匹配/未知 type 全部拒")
    void createValidationMatrix() throws Exception {
        String stu = login(STU_A);
        ItemSaveDTO base = new ItemSaveDTO();
        base.setType("FOUND");
        base.setTitle("校验样本");
        base.setDescription("描述");
        base.setCategoryId(1L);
        base.setHappenedAt(LocalDateTime.now().plusDays(1));   // 未来时间
        base.setVerifyQuestion("？");
        mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(base)))
                .andExpect(jsonPath("$.code").value(400));

        base.setHappenedAt(LocalDateTime.now().minusDays(1));
        base.setCategoryId(999L);                                // 幽灵分类
        mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(base)))
                .andExpect(jsonPath("$.code").value(BizCode.CATEGORY_NOT_FOUND.getCode()));

        base.setCategoryId(1L);
        base.setCampusId(2L);
        base.setBuildingId(11L);   // 11 号楼属主校区(1)，与 campusId=2 南校区不匹配
        mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(base)))
                .andExpect(jsonPath("$.code").value(400));

        base.setBuildingId(null);
        base.setType("OTHER");
        mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(base)))
                .andExpect(status().isBadRequest());   // @Pattern DTO 层先拦
    }

    @Test
    @DisplayName("每日发布上限：第 11 条 429（闸门按当日全部状态计数）")
    void dailyPublishLimit() throws Exception {
        String token = registerAndLogin();
        for (int i = 0; i < 10; i++) {
            createItem(token, "LOST", "上限样本-" + System.nanoTime(), null);
        }
        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ItemSaveDTO() {{
                            setType("LOST");
                            setTitle("第11条");
                            setDescription("超上限");
                            setCategoryId(1L);
                            setHappenedAt(LocalDateTime.now().minusHours(1));
                        }})))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(1005));
    }

    @Test
    @DisplayName("敏感词闸门：描述命中 400 且响应不回显命中词")
    void sensitiveWordGate() throws Exception {
        String stu = login(STU_A);
        MvcResult res = mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ItemSaveDTO() {{
                            setType("LOST");
                            setTitle("丢了耳机");
                            setDescription("有意者办证私聊，价格面议");
                            setCategoryId(2L);
                            setHappenedAt(LocalDateTime.now().minusHours(1));
                        }})))
                .andReturn();
        JsonNode body = envelope(res);
        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).doesNotContain("办证"); // 不回显教人绕词
    }

    @Test
    @DisplayName("图片闸门：非 /uploads/ 前缀 URL 拒；超 6 张拒")
    void imageUrlGuards() throws Exception {
        String stu = login(STU_A);
        MvcResult res = mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ItemSaveDTO() {{
                            setType("LOST");
                            setTitle("带外链图");
                            setDescription("样本");
                            setCategoryId(1L);
                            setHappenedAt(LocalDateTime.now().minusHours(1));
                            setImageUrls(List.of("http://evil.example/x.png"));
                        }})))
                .andReturn();
        assertThat(envelope(res).path("code").asInt()).isEqualTo(400);

        var urls = new java.util.ArrayList<String>();
        for (int i = 0; i < 7; i++) urls.add("/uploads/2026/11/fake" + i + ".png");
        MvcResult res2 = mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ItemSaveDTO() {{
                            setType("LOST");
                            setTitle("七张图");
                            setDescription("样本");
                            setCategoryId(1L);
                            setHappenedAt(LocalDateTime.now().minusHours(1));
                            setImageUrls(urls);
                        }})))
                .andReturn();
        assertThat(envelope(res2).path("code").asInt()).isEqualTo(400);
    }

    // ====================== 可见性矩阵与脱敏 ======================

    @Test
    @DisplayName("可见性矩阵：他人待审/下架物品=2001 防枚举；作者与管理端可见")
    void visibilityMatrix() throws Exception {
        String stuA = login(STU_A);
        // 无关者必须用新注册纯学生——种子 #2 是 MANAGER 干事，天然有治理可见权
        String pureStudent = registerAndLogin();
        long id = createItem(stuA, "FOUND", "枚举防御样本", "问题？");

        // 无关登录者读他人待审 → 404/2001（与"不存在"同响应，防枚举）
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + pureStudent))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(BizCode.ITEM_NOT_FOUND.getCode()));
        // 游客同样 2001
        mockMvc.perform(get("/api/items/" + id))
                .andExpect(status().isNotFound());
        // 作者可见
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + stuA))
                .andExpect(jsonPath("$.code").value(0));
        // 坏 token 401（验签闸顺带钉）
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + pureStudent + "x"))
                .andExpect(status().isUnauthorized());
        // MANAGER（item:audit）可见
        String mgr = login(STU_B);
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("字段脱敏：游客详情 contact=null，登录可见；offlineReason 仅作者/治理")
    void contactMasking() throws Exception {
        String stu = login(STU_A);
        String mgr = login(STU_B);
        long id = createItem(stu, "FOUND", "脱敏样本", "问题？");
        // 先审核发布——待审件对游客是 404（可见性矩阵），脱敏断言必须在发布态做
        publishViaAudit(mgr, id);

        JsonNode guest = envelope(mockMvc.perform(get("/api/items/" + id)).andReturn());
        assertThat(guest.path("data").path("contact").isNull()).isTrue();
        assertThat(guest.path("data").path("verifyQuestion").asText()).isEqualTo("问题？"); // 核验问题公开（申请人要看得到）

        JsonNode member = envelope(mockMvc.perform(get("/api/items/" + id)
                .header("Authorization", "Bearer " + stu)).andReturn());
        assertThat(member.path("data").path("contact").asText()).isEqualTo("13800000000");

        // 下架后：无关治理者(MANAGER)看得到理由，作者也看得到；理由装配不泄露给游客（游客根本读不到下架件）
        mockMvc.perform(patch("/api/items/" + id + "/status")
                        .param("target", "2").param("reason", "信息不全")
                        .header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode ownerView = envelope(mockMvc.perform(get("/api/items/" + id)
                .header("Authorization", "Bearer " + stu)).andReturn());
        assertThat(ownerView.path("data").path("offlineReason").asText()).isEqualTo("信息不全");
    }

    // ====================== 状态机矩阵 ======================

    @Test
    @DisplayName("状态机：0→1 审核通过、1→2 下架理由必填、2→1 重上架、0→1 作者自助被拒")
    void statusMachineMatrix() throws Exception {
        String stu = login(STU_A);
        String mgr = login(STU_B);
        long id = createItem(stu, "FOUND", "状态机样本", "问题？");

        // 作者自助推 0→1：拒（待审物品只属于审核动作）
        mockMvc.perform(patch("/api/items/" + id + "/status")
                        .param("target", "1").header("Authorization", "Bearer " + stu))
                .andExpect(status().isForbidden());

        // 审核通过 0→1
        publishViaAudit(mgr, id);
        // 非法自转 1→1
        mockMvc.perform(patch("/api/items/" + id + "/status")
                        .param("target", "1").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(BizCode.ITEM_STATUS_INVALID.getCode()));
        // 下架缺理由
        mockMvc.perform(patch("/api/items/" + id + "/status")
                        .param("target", "2").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(400));
        // 下架 → 重上架
        mockMvc.perform(patch("/api/items/" + id + "/status")
                        .param("target", "2").param("reason", "疑似广告")
                        .header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(patch("/api/items/" + id + "/status")
                        .param("target", "1").header("Authorization", "Bearer " + mgr))
                .andExpect(jsonPath("$.code").value(0));
        // 下架件回到公开列表（重上架后游客可见）
        MvcResult list = mockMvc.perform(get("/api/items").param("keyword", "状态机样本")).andReturn();
        assertThat(envelope(list).path("data").path("total").asLong()).isEqualTo(1);
    }

    // ====================== 编辑与乐观锁 ======================

    @Test
    @DisplayName("编辑：version 缺失 400、双窗口冲突 2004、type 翻转无效")
    void editOptimisticLockAndTypeImmutable() throws Exception {
        String stu = login(STU_A);
        long id = createItem(stu, "LOST", "乐观锁样本", null);

        JsonNode detail = envelope(mockMvc.perform(get("/api/items/" + id)
                .header("Authorization", "Bearer " + stu)).andReturn());
        int v = detail.path("data").path("version").asInt();

        ItemSaveDTO patch1 = saveFrom(detail);
        patch1.setVersion(v);
        patch1.setTitle("乐观锁样本-窗口1");
        mockMvc.perform(put("/api/items/" + id).header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patch1)))
                .andExpect(jsonPath("$.code").value(0));

        // 第二窗口拿旧 version 再改 → 2004
        ItemSaveDTO patch2 = saveFrom(detail);
        patch2.setVersion(v);
        patch2.setTitle("乐观锁样本-窗口2");
        mockMvc.perform(put("/api/items/" + id).header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patch2)))
                .andExpect(jsonPath("$.code").value(BizCode.VERSION_CONFLICT.getCode()));

        // version 缺失 → 400
        ItemSaveDTO patch3 = saveFrom(detail);
        patch3.setVersion(null);
        mockMvc.perform(put("/api/items/" + id).header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patch3)))
                .andExpect(jsonPath("$.code").value(400));

        // type 不可翻转：编辑时传 FOUND，库里仍是 LOST（Service 白名单不搬 type）
        LostItem after = itemMapper.selectById(id);
        assertThat(after.getType()).isEqualTo("LOST");
    }

    private ItemSaveDTO saveFrom(JsonNode data) {
        JsonNode d = data.path("data");
        ItemSaveDTO dto = new ItemSaveDTO();
        dto.setType(d.path("type").asText());
        dto.setTitle(d.path("title").asText());
        dto.setDescription(d.path("description").asText());
        dto.setCategoryId(d.path("categoryId").asLong());
        dto.setLocationText(d.path("locationText").asText(null));
        dto.setContact(d.path("contact").asText(null));
        dto.setVerifyQuestion(d.path("verifyQuestion").asText(null));
        dto.setHappenedAt(LocalDateTime.now().minusDays(1));
        return dto;
    }

    @Test
    @DisplayName("非作者编辑他人物品 1005/403")
    void editOthersForbidden() throws Exception {
        String stuA = login(STU_A);
        // 新注册纯学生：种子 #2 有 item:offline，走治理编辑分支是合法动作不是越权
        String stuB = registerAndLogin();
        long id = createItem(stuA, "LOST", "他人物品", null);
        LostItem item = itemMapper.selectById(id);

        ItemSaveDTO dto = new ItemSaveDTO();
        dto.setType("LOST");
        dto.setTitle("被篡改");
        dto.setDescription("篡改描述");
        dto.setCategoryId(1L);
        dto.setHappenedAt(LocalDateTime.now().minusDays(1));
        dto.setVersion(item.getVersion());
        mockMvc.perform(put("/api/items/" + id).header("Authorization", "Bearer " + stuB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(BizCode.ITEM_NOT_OWNER.getCode()));
    }

    // ====================== 删除与检索 ======================

    @Test
    @DisplayName("删除=逻辑删：详情 404、公开列表消失、物理行 deleted=1 保留")
    void softDeleteSemantics() throws Exception {
        String stu = login(STU_A);
        long id = createItem(stu, "LOST", "将被删除的样本", null);

        mockMvc.perform(delete("/api/items/" + id).header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + stu))
                .andExpect(status().isNotFound());
        Integer raw = jdbcTemplate.queryForObject(
                "select deleted from lost_item where id=?", Integer.class, id);
        assertThat(raw).isEqualTo(1);   // 回收站/审计物理痕迹
    }

    @Test
    @DisplayName("检索矩阵：type/category/campus 过滤与 keyword LIKE 命中；hot 排序表达式可执行")
    void queryMatrix() throws Exception {
        String stu = login(STU_A);
        String mgr = login(STU_B);
        long card = createItem(stu, "FOUND", "图书馆捡到饭卡", "卡号后四位？");
        publishViaAudit(mgr, card);

        MvcResult byType = mockMvc.perform(get("/api/items")
                        .param("keyword", "图书馆捡到饭卡").param("type", "FOUND"))
                .andReturn();
        assertThat(envelope(byType).path("data").path("total").asLong()).isEqualTo(1);

        MvcResult wrongType = mockMvc.perform(get("/api/items")
                        .param("keyword", "图书馆捡到饭卡").param("type", "LOST"))
                .andReturn();
        assertThat(envelope(wrongType).path("data").path("total").asLong()).isZero();

        MvcResult byCategory = mockMvc.perform(get("/api/items")
                        .param("keyword", "饭卡").param("categoryId", "1"))
                .andReturn();
        assertThat(envelope(byCategory).path("data").path("total").asLong()).isEqualTo(1);

        // hot 排序：last() 表达式的 H2 可执行性钉（MySQL/H2 双库同语法回归）
        MvcResult hot = mockMvc.perform(get("/api/items").param("sort", "hot"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        assertThat(envelope(hot).path("data").path("list").size()).isGreaterThan(0);

        // 时间窗过滤
        MvcResult future = mockMvc.perform(get("/api/items")
                        .param("keyword", "饭卡")
                        .param("from", LocalDateTime.now().plusDays(1).toString()))
                .andReturn();
        assertThat(envelope(future).path("data").path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("详情浏览计数原子自增（SQL setSql 版，M9 换 Redis 计数前的事实口径）")
    void viewCountIncrements() throws Exception {
        String stu = login(STU_A);
        long id = createItem(stu, "LOST", "计数样本", null);
        publishViaAudit(login(STU_B), id);

        mockMvc.perform(get("/api/items/" + id)).andExpect(jsonPath("$.code").value(0));
        MvcResult r2 = mockMvc.perform(get("/api/items/" + id)).andReturn();
        assertThat(envelope(r2).path("data").path("viewCount").asInt()).isGreaterThanOrEqualTo(2);

        int dbCount = itemMapper.selectById(id).getViewCount();
        assertThat(dbCount).isGreaterThanOrEqualTo(2);
    }

    // ====================== 字典与清洗 ======================

    @Test
    @DisplayName("字典隔离：categories?kind=ITEM 与 BOARD 不串；areas 平铺两级")
    void dictIsolation() throws Exception {
        MvcResult items = mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk()).andReturn();   // 游客可读（白名单）
        assertThat(envelope(items).path("data").size()).isEqualTo(6);

        MvcResult boards = mockMvc.perform(get("/api/categories").param("kind", "BOARD"))
                .andReturn();
        assertThat(envelope(boards).path("data").size()).isEqualTo(3);

        MvcResult areas = mockMvc.perform(get("/api/areas")).andReturn();
        assertThat(envelope(areas).path("data").size()).isEqualTo(6);  // 2 校区 + 4 楼栋
    }

    @Test
    @DisplayName("TextCleaner：标签剥离/实体解码后再剥/换行折叠/全角空格保留")
    void textCleanerUnit() {
        // jsoup clean 不收集 script/style 内容（博客 M3 MarkdownProcessor 同性质）：
        // 脚本标签与脚本体一并消失，只留下正常文字——这是比"留 alert(1) 文本"更强的清洗
        assertThat(TextCleaner.clean("<script>alert(1)</script>正常文字"))
                .isEqualTo("正常文字");
        // 实体混淆：&lt;script&gt; 先解码再剥（jsoup clean 的性质）
        assertThat(TextCleaner.clean("&lt;b&gt;粗&lt;/b&gt;"))
                .isEqualTo("粗");
        assertThat(TextCleaner.clean("a\n\n\n\n\nb")).isEqualTo("a b");
        // 口径说明：Jsoup.clean 在输出侧做 HTML 空白归一化（换行→空格），这是
        // "清洗后入库"的既定行为；换行折叠规则只对"经文本通道保留下来的换行"
        // 生效（如 M5 论坛正文走"先剥危险构造、再按原文换行治理"的双轨时才有意义），
        // 物品描述这类单段文本不依赖换行——测试固化实测口径而非理想口径。
        assertThat(TextCleaner.clean("a  \nb")).isEqualTo("a b");
        assertThat(TextCleaner.clean("尾随空格   行")).doesNotContain("   ");
        assertThat(TextCleaner.clean("全角　空格")).contains("\u3000"); // 已知边界固化（M11 口径）
        assertThat(TextCleaner.visibleLength("a b  c")).isEqualTo(3);
    }

    // ====================== 治理开关直发路径 ======================

    @Test
    @DisplayName("item_audit=0 时发布可直发(1)；开关=1 时客户端意图被静默降级(仍0)")
    void auditSwitchDirectPublish() throws Exception {
        String stu = login(STU_A);
        ItemSaveDTO dto = new ItemSaveDTO();
        dto.setType("LOST");
        dto.setTitle("直发样本");
        dto.setDescription("开关关闭直接发布");
        dto.setCategoryId(1L);
        dto.setHappenedAt(LocalDateTime.now().minusHours(1));
        dto.setStatus(1);

        // 开关=1（种子默认）：降级到待审
        long downgraded = envelope(mockMvc.perform(post("/api/items")
                .header("Authorization", "Bearer " + stu)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto))).andReturn())
                .path("data").path("status").asLong();
        assertThat(downgraded).isEqualTo(ItemService.STATUS_PENDING);

        // 临时关开关（事务内改，回滚不留痕）
        jdbcTemplate.update("update sys_config set config_value='0' where config_key='item_audit'");
        JsonNode direct = envelope(mockMvc.perform(post("/api/items")
                .header("Authorization", "Bearer " + stu)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto))).andReturn());
        assertThat(direct.path("data").path("status").asLong()).isEqualTo(ItemService.STATUS_PUBLISHED);
    }
}

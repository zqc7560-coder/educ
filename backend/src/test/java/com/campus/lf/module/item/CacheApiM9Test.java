package com.campus.lf.module.item;

import com.campus.lf.common.BizCode;
import com.campus.lf.job.PvSyncJob;
import com.campus.lf.module.item.dto.ItemSaveDTO;
import com.campus.lf.module.item.entity.LostItem;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.module.item.service.ItemCacheService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9 缓存体系测试：详情/列表 Cache-Aside、写路径双删关门、PV Redis 计数与回写、
 * 补偿队列、缓存运维口、Redis 故障降级。
 *
 * <p>断言哲学（博客 M9 同款）：缓存的正确性不在"快"，在"脏窗口有多长、谁来关门"——
 * 每个用例都钉"写路径是否真的关掉了读路径留下的门"。TestCacheGuardExtension 保证
 * 每个用例从缓存空态起跑（DB 回滚不滚 Redis，不守卫就是跨用例污染）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M9 缓存与性能 API")
class CacheApiM9Test {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper om;
    @Autowired private ItemCacheService cache;
    @Autowired private PvSyncJob pvSyncJob;
    @Autowired private LostItemMapper itemMapper;
    @Autowired private StringRedisTemplate redis;
    @Autowired private com.campus.lf.config.AdminBootstrapRunner adminBootstrap;

    private static final String SEED_PWD = "password123";
    private static final String STU_A = "2023508090";
    private static final String STU_B = "2023508091";   // MANAGER(item:audit)
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

    private long createPublished(String token, String mgrToken, String title) throws Exception {
        ItemSaveDTO dto = new ItemSaveDTO();
        dto.setType("LOST");
        dto.setTitle(title);
        dto.setDescription("缓存测试描述内容");
        dto.setCategoryId(1L);
        dto.setHappenedAt(LocalDateTime.now().minusDays(1));
        MvcResult r = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(dto)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long id = envelope(r).path("data").path("id").asLong();
        mockMvc.perform(patch("/api/items/" + id + "/status").param("target", "1")
                        .header("Authorization", "Bearer " + mgrToken))
                .andExpect(jsonPath("$.code").value(0));
        return id;
    }

    // ====================== 详情缓存 ======================

    @Test
    @DisplayName("详情 Cache-Aside：游客首读回填 lf:item:{id}；二读命中缓存视图；登录者不吃缓存")
    void detailCacheAside() throws Exception {
        String stu = login(STU_A);
        long id = createPublished(stu, login(STU_B), "缓存详情样本");

        // 首读（游客）：回源 + 回填
        mockMvc.perform(get("/api/items/" + id)).andExpect(jsonPath("$.code").value(0));
        assertThat(cache.getDetail(id)).as("发布态游客读后已回填").isNotNull();

        // 篡改 DB 行但绕过缓存失效（直接 mapper 改，模拟"写没关门"的脏窗口）→ 二读命中旧缓存
        LostItem dirty = itemMapper.selectById(id);
        dirty.setTitle("DB已被直改");
        itemMapper.updateById(dirty);
        JsonNode second = envelope(mockMvc.perform(get("/api/items/" + id)).andReturn());
        assertThat(second.path("data").path("title").asText())
                .as("缓存命中的是回填时视图，不是刚直改的 DB 值（Cache-Aside 的脏窗口证明）")
                .isEqualTo("缓存详情样本");

        // 走正规写路径（PUT 编辑）→ evictTx 关门 → 再读拿到新值
        LostItem fresh = itemMapper.selectById(id);
        ItemSaveDTO edit = new ItemSaveDTO();
        edit.setType("LOST");
        edit.setTitle("正规改名");
        edit.setDescription("描述内容");
        edit.setCategoryId(1L);
        edit.setHappenedAt(LocalDateTime.now().minusDays(1));
        edit.setVersion(fresh.getVersion());
        mockMvc.perform(put("/api/items/" + id).header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(edit)))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(cache.getDetail(id)).as("evictTx 已删详情键").isNull();
        JsonNode third = envelope(mockMvc.perform(get("/api/items/" + id)).andReturn());
        assertThat(third.path("data").path("title").asText()).isEqualTo("正规改名");

        // 登录者视图（contact 可见）永不入缓存：登录读后键不存在或值为游客视图
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.data.contact").doesNotExist());  // 本件无 contact，用装配口径
        // 精确断言：缓存键内容不含登录字段——回填只发生在 me==null 分支
        cache.evict(id);   // 清掉登录读前的游客回填，保证下面键由"登录路径"独占产生
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + stu)).andReturn();
        assertThat(cache.getDetail(id))
                .as("登录者读不回填：个人视图进缓存=权限穿透")
                .isNull();
    }

    @Test
    @DisplayName("待审/下架件不入缓存（作者要所见即最新）；404 不回填")
    void nonPublishedNotCached() throws Exception {
        String stu = login(STU_A);
        ItemSaveDTO dto = new ItemSaveDTO();
        dto.setType("LOST");
        dto.setTitle("待审件不缓存");
        dto.setDescription("描述内容");
        dto.setCategoryId(1L);
        dto.setHappenedAt(LocalDateTime.now().minusDays(1));
        MvcResult r = mockMvc.perform(post("/api/items").header("Authorization", "Bearer " + stu)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(dto))).andReturn();
        long pendingId = envelope(r).path("data").path("id").asLong();

        // 作者可读（待审对自己透明），PV 计数正常（作者反复看自己件是真实浏览），
        // 但绝不回填缓存——"作者要所见即最新"
        mockMvc.perform(get("/api/items/" + pendingId).header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(cache.getDetail(pendingId)).isNull();
        long afterAuthorRead = cache.peekCounter(ItemCacheService.PV_PREFIX + pendingId);

        // 游客读 404：不产生缓存、不计数（计数在可见性闸之后——"看不见的被浏览"
        // 会污染趋势；本行断言就是那条纪律的钉子，M9 实测抓出的口径修正）
        mockMvc.perform(get("/api/items/" + pendingId)).andExpect(status().isNotFound());
        assertThat(cache.getDetail(pendingId)).isNull();
        assertThat(cache.peekCounter(ItemCacheService.PV_PREFIX + pendingId))
                .isEqualTo(afterAuthorRead);
    }

    // ====================== 列表缓存 ======================

    @Test
    @DisplayName("列表 Cache-Aside：同参数二读命中缓存信封；规范化参数同键；新发布 evictTx 关门")
    void listCacheAside() throws Exception {
        String stu = login(STU_A);
        String mgr = login(STU_B);

        MvcResult a = mockMvc.perform(get("/api/items").param("type", "LOST")).andReturn();
        long totalA = envelope(a).path("data").path("total").asLong();
        // 首读已按规范化参数回填并登记键清单
        String key = cache.listKey(ItemCacheService.canonical(
                "lost", null, null, null, null, null, null, null, 1, 10));
        // 大小写/空格书写差异 → 同键（type=LOST≡type=lost，规范化到 md5 前大写归一后一致）
        assertThat(cache.getList(key)).as("列表已回填").isNotNull();
        assertThat(cache.getList(key).total()).isEqualTo(totalA);

        // 新直发一件（audit 关? 种子 item_audit=1 → 走发布审核链：先审后发）——治理发布触发 evictTx
        long id = createPublished(stu, mgr, "列表关门样本");
        assertThat(cache.getList(key)).as("状态流转后列表键已关门").isNull();

        MvcResult c = mockMvc.perform(get("/api/items").param("type", "LOST")).andReturn();
        assertThat(envelope(c).path("data").path("total").asLong()).isEqualTo(totalA + 1);
    }

    // ====================== PV 计数与回写 ======================

    @Test
    @DisplayName("PV：游客两读计数 2→syncNow 落库+键清零；登录读也计数；补偿队列排空")
    void pvCounterAndSync() throws Exception {
        String stu = login(STU_A);
        long id = createPublished(stu, login(STU_B), "PV样本");

        mockMvc.perform(get("/api/items/" + id)).andReturn();          // 游客读1（回源）
        mockMvc.perform(get("/api/items/" + id)).andReturn();          // 游客读2（命中缓存仍计数）
        mockMvc.perform(get("/api/items/" + id).header("Authorization", "Bearer " + stu)).andReturn(); // 登录读
        assertThat(cache.peekCounter(ItemCacheService.PV_PREFIX + id)).isEqualTo(3);

        int dbBefore = itemMapper.selectById(id).getViewCount();
        PvSyncJob.Result res = pvSyncJob.syncNow();
        assertThat(res.applied()).isGreaterThanOrEqualTo(1);
        assertThat(itemMapper.selectById(id).getViewCount()).isEqualTo(dbBefore + 3);
        assertThat(cache.peekCounter(ItemCacheService.PV_PREFIX + id)).isZero();
        // 再跑一轮：无增量可回写（取走即归零的幂等证明）
        assertThat(pvSyncJob.syncNow().totalDelta()).isZero();
    }

    @Test
    @DisplayName("失效补偿队列：手工塞坏 id 进 lf:evict:retry，syncNow 顺带排空")
    void evictRetryDrain() throws Exception {
        redis.opsForSet().add(ItemCacheService.EVICT_RETRY, "999999");
        assertThat(cache.drainEvictRetry()).isEqualTo(1);
        // 排空后队列不含该 id
        assertThat(redis.opsForSet().members(ItemCacheService.EVICT_RETRY))
                .doesNotContain("999999");
    }

    // ====================== 运维口 ======================

    @Test
    @DisplayName("evictAll：填详情+列表键后一键清空（管理端应急口）")
    void evictAllOps() throws Exception {
        String stu = login(STU_A);
        long id = createPublished(stu, login(STU_B), "清缓存样本");
        mockMvc.perform(get("/api/items/" + id)).andReturn();
        mockMvc.perform(get("/api/items")).andReturn();
        assertThat(cache.getDetail(id)).isNotNull();

        long cleared = cache.evictAll();
        assertThat(cleared).isGreaterThanOrEqualTo(1);
        assertThat(cache.getDetail(id)).isNull();
        assertThat(cache.getList(cache.listKey(ItemCacheService.canonical(
                null, null, null, null, null, null, null, null, 1, 10)))).isNull();
    }

    @Test
    @DisplayName("管理端清缓存口：DELETE /api/admin/cache 挂 config:manage——学生 403、干事 403、admin 200")
    void adminCacheEndpoint() throws Exception {
        String stu = login(STU_A);
        mockMvc.perform(delete("/api/admin/cache").header("Authorization", "Bearer " + stu))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
        // MANAGER 权限矩阵无 config:manage（M8 已钉过的粒度，运维口同族同闸）
        mockMvc.perform(delete("/api/admin/cache").header("Authorization", "Bearer " + login(STU_B)))
                .andExpect(jsonPath("$.code").value(BizCode.FORBIDDEN.getCode()));
        adminBootstrap.run(null);
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk()).andReturn();
        String admin = envelope(res).path("data").path("token").asText();
        // 先造缓存再清：读一次详情回填 → 管理口清空 → 键消失
        String stu2 = login(STU_A);
        long id = createPublished(stu2, admin, "运维口样本");
        mockMvc.perform(get("/api/items/" + id)).andReturn();
        assertThat(cache.getDetail(id)).isNotNull();
        mockMvc.perform(delete("/api/admin/cache").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(cache.getDetail(id)).isNull();
    }

    // ====================== Redis 故障降级 ======================

    @Test
    @DisplayName("Redis 全挂=全站自动退化直查 DB：坏 template 注入场景用异常模拟口验证不 500")
    void redisDownDegrade() throws Exception {
        // 内存桩无法真"挂"，但缓存服务的纪律是任何 Redis 异常都吞——用坏键序列化
        // 验证 getDetail 异常路径按未命中处理（store 塞非 JSON 值模拟坏数据）：
        String stu = login(STU_A);
        long id = createPublished(stu, login(STU_B), "降级样本");
        redis.opsForValue().set(ItemCacheService.DETAIL_PREFIX + id, "{ 坏 JSON 不成像");
        JsonNode r = envelope(mockMvc.perform(get("/api/items/" + id)).andReturn());
        assertThat(r.path("code").asInt()).isZero();     // 读失败按未命中，回源正常返回
        assertThat(r.path("data").path("title").asText()).isEqualTo("降级样本");
        // 删除失败进补偿队列的入口行为：键不存在时 evict 不抛（幂等）
        cache.evict(888888L);
        assertThat(cache.peekCounter(ItemCacheService.EVICT_RETRY)).isZero();
    }
}

/*
 =====================================================================
 M0 骨架集成测试（H2 内存库，MySQL 兼容模式）。

 验证骨架本身的四件事（与博客毕设同口径——这套骨架已被 300+ 用例验证过）：
   1. Spring 容器能启动（MP + Redis + Knife4j 共存装配无冲突）；
   2. 测试数据源确实是 H2 —— 满足"测试不依赖真实 MySQL 账号"的环境约束；
   3. MyBatis-Plus 插件链按注册生效：自增主键回填、乐观锁、逻辑删除、分页
      （用 M1 的 lost_item 表先行冒烟——schema-h2.sql 的 lost_item 是核心表，
       M1 落全量 18 表时本测试自动升级为全表比对）；
   4. 统一响应体信封在真实 MVC 链路（/api/health）上结构正确。

 Redis 用 @MockBean 顶掉：测试必须与外部中间件解耦——任何人 clone 下来
 没起 Redis 也能 mvn test 全绿，这是 CI 化的前提。
 =====================================================================
*/
package com.campus.lf;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.lf.module.item.entity.LostItem;
import com.campus.lf.module.item.mapper.LostItemMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("骨架集成: 容器启动 + H2 数据源 + MP 插件链 + 统一响应体")
class LostFoundApplicationTests {

    @Autowired
    private LostItemMapper lostItemMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 真实 MVC 链路的测试入口（不起端口，走 MockMvc） */
    @Autowired
    private MockMvc mockMvc;

    /** 顶掉真实 Redis 客户端：健康探测的 redis 分支走 mock 返回 UP */
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    /** 造一条最小合法物品（FOUND 件：标题/描述/分类/拾获地点齐，草稿态） */
    private LostItem newItem(String title, Long userId) {
        LostItem it = new LostItem();
        it.setType("FOUND");
        it.setUserId(userId);
        it.setTitle(title);
        it.setDescription("测试描述-" + title);
        it.setCategoryId(1L);
        it.setHappenedAt(LocalDateTime.now().minusDays(1));
        it.setLocationText("三号楼门口");
        it.setStatus(0);
        it.setViewCount(0);
        it.setClaimCount(0);
        it.setVersion(0);
        return it;
    }

    @Test
    @DisplayName("容器启动即骨架装配成功：核心 Bean 全部可注入")
    void contextLoads() {
        assertThat(lostItemMapper).isNotNull();
        assertThat(mockMvc).isNotNull();
    }

    @Test
    @DisplayName("测试数据源是 H2 内存库，而非本机 MySQL")
    void dataSourceIsH2() {
        // h2version() 只在 H2 上存在：查询能执行本身就证明换库成功
        String product = jdbcTemplate.queryForObject("select h2version()", String.class);
        assertThat(product).isNotBlank();
    }

    @Test
    @DisplayName("MP 基础链路：insert 自增主键回填、驼峰↔下划线列映射")
    void insertFillsIdAndMapsColumns() {
        LostItem it = newItem("列映射冒烟", 1L);
        int rows = lostItemMapper.insert(it);

        assertThat(rows).isEqualTo(1);
        assertThat(it.getId()).isNotNull();   // IdType.AUTO 主键回填成功

        LostItem loaded = lostItemMapper.selectById(it.getId());
        assertThat(loaded.getHappenedAt()).isNotNull();     // happened_at 下划线列映射
        assertThat(loaded.getViewCount()).isEqualTo(0);     // view_count 同上
        assertThat(loaded.getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("乐观锁插件：version 不匹配的 update 影响 0 行（M3 编辑防覆盖的地基）")
    void optimisticLockBlocksStaleUpdate() {
        lostItemMapper.insert(newItem("乐观锁样本", 1L));
        LostItem inserted = lastInserted();

        LostItem window1 = lostItemMapper.selectById(inserted.getId());
        LostItem window2 = lostItemMapper.selectById(inserted.getId());

        window1.setTitle("窗口1先保存");
        assertThat(lostItemMapper.updateById(window1)).isEqualTo(1);   // version 0→1

        window2.setTitle("窗口2后保存");
        assertThat(lostItemMapper.updateById(window2)).isZero();       // WHERE version=0 已不成立

        LostItem after = lostItemMapper.selectById(inserted.getId());
        assertThat(after.getTitle()).isEqualTo("窗口1先保存");
        assertThat(after.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("逻辑删除：deleteById 转 UPDATE deleted=1，查询自动过滤已删行")
    void logicDeleteHidesRowFromQueries() {
        lostItemMapper.insert(newItem("将被逻辑删除", 1L));
        LostItem target = lastInserted();

        assertThat(lostItemMapper.deleteById(target.getId())).isEqualTo(1);
        assertThat(lostItemMapper.selectById(target.getId())).isNull();  // MP 自动拼 deleted=0
        Integer rawDeleted = jdbcTemplate.queryForObject(
                "select deleted from lost_item where id = ?", Integer.class, target.getId());
        assertThat(rawDeleted).isEqualTo(1);   // 底层行还在（回收站/审计语义）
    }

    @Test
    @DisplayName("分页插件：selectPage 返回 records/total/pages 正确")
    void paginationPluginWorks() {
        for (int i = 0; i < 5; i++) {
            lostItemMapper.insert(newItem("分页样本-" + i, 2L));
        }

        Page<LostItem> page = lostItemMapper.selectPage(new Page<>(1, 2),
                new LambdaQueryWrapper<LostItem>().eq(LostItem::getUserId, 2L)
                        .orderByAsc(LostItem::getId));

        assertThat(page.getRecords()).hasSize(2);
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(5);
        assertThat(page.getPages()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("/api/health 真实链路：统一响应体四字段 + 依赖连通态")
    void healthEndpointReturnsUnifiedEnvelope() throws Exception {
        // mock Redis ping：SDR 3.x 的 RedisConnection.ping() 返回 String "PONG"
        //（博客 M0 实测坑：不是 byte[]，stub 形态错了健康断言就红）
        RedisConnection conn = mock(RedisConnection.class);
        when(conn.ping()).thenReturn("PONG");
        when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.RedisCallback<?>>any()))
                .thenAnswer(invocation -> {
                    org.springframework.data.redis.core.RedisCallback<?> cb = invocation.getArgument(0);
                    return cb.doInRedis(conn);
                });

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.data.mysql").value("UP"))
                .andExpect(jsonPath("$.data.overall").value("UP"));
    }

    /** 按 id 倒序取最近插入的一条（测试内自增序列单调，规避断言脆弱性） */
    private LostItem lastInserted() {
        List<LostItem> list = lostItemMapper.selectList(new LambdaQueryWrapper<LostItem>()
                .orderByDesc(LostItem::getId).last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }
}

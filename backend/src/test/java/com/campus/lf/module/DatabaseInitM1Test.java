package com.campus.lf.module;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 数据库初始化契约测试（手法继承博客毕设 DatabaseInitM1Test，被 300+ 用例验证）。
 *
 * <p>四道机械闸，全部零业务、纯结构断言：
 * <ol>
 *   <li><b>实体↔表列双向比对</b>：TableInfoHelper 拿每个实体映射的表与列集合，
 *       与 H2 信息模式实有列做双向差集——实体多列（DDL 忘同步）与表多列（实体漏字段）
 *       都当场红。生产 schema 与 H2 schema 的漂移由测试加载的正是 H2 版这一事实
 *       + 两侧脚本同源维护纪律兜住；</li>
 *   <li><b>22 表齐全</b>：信息模式数表；</li>
 *   <li><b>种子矩阵</b>：角色 3/权限 13/授权矩阵条数/分类 6+板块 3/校区 2 楼栋 4/
 *       参数 7/公告 1/敏感词 3——口径与 data-h2.sql 逐条对齐；</li>
 *   <li><b>Key 红线</b>：种子里不得出现 sk- 等真实密钥形态（本项目无 AI Key，
 *       该闸守的是口令散列形态：必须 BCrypt 且非明文）。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("M1: 22表结构与种子契约")
class DatabaseInitM1Test {

    /** 实体清单：module→类（与 db/mysql/schema.sql 22 表一一对应；LostItem 手写其余生成器批量） */
    private static final Class<?>[] ENTITIES = {
            com.campus.lf.module.user.entity.SysUser.class,
            com.campus.lf.module.user.entity.SysRole.class,
            com.campus.lf.module.user.entity.SysUserRole.class,
            com.campus.lf.module.user.entity.SysPermission.class,
            com.campus.lf.module.user.entity.SysRolePermission.class,
            com.campus.lf.module.item.entity.Category.class,
            com.campus.lf.module.item.entity.CampusArea.class,
            com.campus.lf.module.item.entity.LostItem.class,
            com.campus.lf.module.item.entity.ItemImage.class,
            com.campus.lf.module.claim.entity.ItemClaim.class,
            com.campus.lf.module.claim.entity.ClaimLog.class,
            com.campus.lf.module.forum.entity.ForumPost.class,
            com.campus.lf.module.forum.entity.PostImage.class,
            com.campus.lf.module.forum.entity.ForumReply.class,
            com.campus.lf.module.forum.entity.ForumLike.class,
            com.campus.lf.module.forum.entity.UserFavorite.class,
            com.campus.lf.module.announce.entity.Announcement.class,
            com.campus.lf.module.announce.entity.AnnouncementImage.class,
            com.campus.lf.module.notice.entity.Notification.class,
            com.campus.lf.module.admin.entity.SensitiveWord.class,
            com.campus.lf.module.admin.entity.SysConfig.class,
            com.campus.lf.module.admin.entity.OperationLog.class,
    };

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("22 表齐全（信息模式数表）")
    void allTablesExist() {
        Set<String> actual = new HashSet<>(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema='PUBLIC'",
                String.class));
        Set<String> expected = new HashSet<>(Arrays.asList(
                "sys_user", "sys_role", "sys_user_role", "sys_permission", "sys_role_permission",
                "category", "campus_area", "lost_item", "item_image",
                "item_claim", "claim_log",
                "forum_post", "post_image", "forum_reply", "forum_like", "user_favorite",
                "announcement", "announcement_image",
                "notification", "sensitive_word", "sys_config", "operation_log"));
        assertThat(expected).hasSize(22);
        assertThat(actual).containsAll(expected);
    }

    @Test
    @DisplayName("实体↔表列双向机械比对（博客 M1 同款 TableInfoHelper 手法）")
    void entityColumnsMatchTables() {
        for (Class<?> e : ENTITIES) {
            // 纯 mock 场景需要预热；本测试有容器，TableInfo 已注册，取不到时手工初始化
            TableInfo info = TableInfoHelper.getTableInfo(e);
            if (info == null) {
                info = TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new MybatisConfiguration(), ""), e);
            }
            String table = info.getTableName();
            Set<String> entityCols = new HashSet<>();
            entityCols.add(info.getKeyColumn());
            entityCols.addAll(info.getFieldList().stream()
                    .map(TableFieldInfo::getColumn).collect(Collectors.toSet()));

            Set<String> dbCols = jdbcTemplate.queryForList(
                    "select column_name from information_schema.columns "
                            + "where table_schema='PUBLIC' and table_name='" + table + "'",
                    String.class).stream().map(String::toLowerCase).collect(Collectors.toSet());

            // 双向差集都应为空：实体有列而表没有 = DDL 忘同步；表有列而实体没有 = 漏字段
            Set<String> entityOnly = new HashSet<>(entityCols);
            entityOnly.removeAll(dbCols);
            Set<String> dbOnly = new HashSet<>(dbCols);
            dbOnly.removeAll(entityCols);
            assertThat(entityOnly).as("实体 %s 声明了表 %s 不存在的列", e.getSimpleName(), table).isEmpty();
            // 表侧多出的列允许是 MySQL 专有默认列（目前没有），纪律上同样要求为空
            assertThat(dbOnly).as("表 %s 有实体 %s 未映射的列", table, e.getSimpleName()).isEmpty();
        }
    }

    @Test
    @DisplayName("种子矩阵逐条核对（计数按种子口径过滤，防同 JVM 冒烟样本串扰）")
    void seedRowsMatchContract() {
        // 角色/权限/授权
        assertThat(count("sys_role", "deleted=0")).isEqualTo(3);
        assertThat(count("sys_permission", "deleted=0")).isEqualTo(13);
        assertThat(count("sys_role_permission", null)).isEqualTo(22);   // MANAGER 9 + ADMIN 13
        // 字典：分类 6 + 板块 3（kind 隔离互不串）
        assertThat(count("category", "kind='ITEM' and deleted=0")).isEqualTo(6);
        assertThat(count("category", "kind='BOARD' and deleted=0")).isEqualTo(3);
        // 校区 2 + 楼栋 4
        assertThat(count("campus_area", "level=1 and deleted=0")).isEqualTo(2);
        assertThat(count("campus_area", "level=2 and deleted=0")).isEqualTo(4);
        // 参数 7 键白名单逐键钉（配置键是治理契约，改名即红）
        List<String> keys = jdbcTemplate.queryForList(
                "select config_key from sys_config where deleted=0 order by id", String.class);
        assertThat(keys).containsExactly("site_name", "item_audit", "forum_audit",
                "claim_timeout_hours", "daily_publish_limit", "announcement_enabled", "match_recommend_top");
        // 公告 1（已发布置顶）
        assertThat(count("announcement", "status=1 and top_flag=1 and deleted=0")).isEqualTo(1);
        // 敏感词 3
        assertThat(count("sensitive_word", "deleted=0")).isEqualTo(3);
        // 演示用户 4（含封禁/禁言态各 1，供 M2 治理用例）。
        // 计数一律按种子 id 段(1..4)过滤——同 JVM 共享 lf_test 库
        // （DB_CLOSE_DELAY=-1），其它测试类（如 AuthApiM2Test 的 admin 引导/
        // 注册冒烟）的落库样本会串进来，绝对计数必被污染（博客 M1 坑 #7 原样复现）。
        assertThat(count("sys_user", "deleted=0 and id between 1 and 4")).isEqualTo(4);
        assertThat(count("sys_user", "status=0 and deleted=0 and id between 1 and 4")).isEqualTo(1);
        assertThat(count("sys_user", "status=2 and deleted=0 and id between 1 and 4")).isEqualTo(1);
    }

    @Test
    @DisplayName("口令形态红线：种子全部 BCrypt 散列，无明文口令")
    void seedPasswordsAreBcrypt() {
        List<String> pwd = jdbcTemplate.queryForList(
                "select password from sys_user where deleted=0", String.class);
        assertThat(pwd).isNotEmpty();
        for (String p : pwd) {
            assertThat(p).as("BCrypt 形态（$2a$10$ 前缀）").startsWith("$2a$10$");
            assertThat(p).doesNotContain("password").doesNotContain("1234");
        }
    }

    /** 按条件数行（条件串直接拼进 SQL——仅测试内部受控字面量，无注入面） */
    private long count(String table, String where) {
        String sql = "select count(*) from " + table + (where == null ? "" : " where " + where);
        Long v = jdbcTemplate.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }
}

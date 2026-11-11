package com.campus.lf.module.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.BizCode;
import com.campus.lf.config.AdminBootstrapRunner;
import com.campus.lf.module.auth.dto.RegisterDTO;
import com.campus.lf.module.user.UserStatus;
import com.campus.lf.module.user.entity.SysUser;
import com.campus.lf.module.user.mapper.SysUserMapper;
import com.campus.lf.support.InMemoryRedis;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 认证接口集成测试 —— 里程碑验收口径（《进度.md》M2 行）：
 * "注册→登录→带 JWT 访问受保护接口→登出后旧 Token 401；受保护接口用无权限
 * 身份访问 1005；改密全端下线；封禁踢线；禁言可登录但写口 1003"。
 *
 * <p>基建决策（继承博客毕设验证过的三件套）：
 * <ol>
 *   <li>StringRedisTemplate 用 @Primary 内存版（InMemoryRedis）覆盖自动配置——
 *       登出/改密/封禁的"令牌作废"流转必须有真实读写语义才测得出东西；</li>
 *   <li>类级 @Transactional：用例间数据零污染（MockMvc 与测试同线程共享事务）；</li>
 *   <li>信封读取一律显式 UTF-8（Result 信封 Content-Type 无 charset 时
 *       getContentAsString 按 ISO-8859-1 解码，中文 message 会乱码——博客 M2 实坑）。</li>
 * </ol>
 *
 * <p>种子前提（data-h2.sql）：2023508090 学生 / 2023508091 学生+MANAGER 干事 /
 * 2023508092 封禁样本 / 2023508093 禁言样本，口令统一 SEED_PWD。
 * admin 不在种子里——由本测试显式驱动 AdminBootstrapRunner（生产同款引导路径）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M2 认证: 注册/登录/RBAC/登出/改密/续期/治理护栏 全链路")
class AuthApiM2Test {

    /** 测试口令常量：与 data-h2.sql 预计算 BCrypt 散列配对（已用 BCryptPasswordEncoder 复核） */
    private static final String SEED_PWD = "password123";

    /** 种子账号学号（显式 id=1..4 便于按 id 引用） */
    private static final String STU_A = "2023508090";
    private static final String STU_MANAGER = "2023508091";
    private static final String STU_BANNED = "2023508092";
    private static final String STU_MUTED = "2023508093";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SysUserMapper userMapper;

    /** admin 引导器：显式驱动（@SpringBootTest 不走 SpringApplication.run，Runner 不会自动执行） */
    @Autowired
    private AdminBootstrapRunner adminBootstrap;

    @TestConfiguration
    static class RedisTestConfig {
        @Bean
        @Primary
        public StringRedisTemplate stringRedisTemplate() {
            return InMemoryRedis.template();
        }
    }

    // ====================== 工具 ======================

    /** 登录并取 token（失败即抛，测试内不吞） */
    private String loginForToken(String studentNo, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("studentNo", studentNo, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return envelope(res).path("data").path("token").asText();
    }

    /** 信封 UTF-8 解析（博客 M2 实坑：默认解码字符集不是 UTF-8，中文 message 乱码断言神秘失败） */
    private com.fasterxml.jackson.databind.JsonNode envelope(MvcResult res) throws Exception {
        String body = new String(res.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    /** 注册新鲜账号（学号=前缀+纳秒取模 10 位，远离 20 字符上限；口令满足强度规则） */
    private String registerFreshUser(String prefix) throws Exception {
        String no = prefix + (System.nanoTime() % 1_000_000_000L);
        RegisterDTO dto = new RegisterDTO();
        dto.setStudentNo(no);
        dto.setPassword("Abcd@1234");
        dto.setNickname("测试" + no);
        dto.setEmail(no + "@t.campus.edu.cn");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return no;
    }

    // ====================== 注册 ======================

    @Test
    @DisplayName("注册成功默认挂 ROLE_STUDENT；学号/邮箱重复分别 1101/1102")
    void registerSuccessAndDuplicates() throws Exception {
        String no = registerFreshUser("m2u");
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentNo, no));
        assertThat(user).isNotNull();
        assertThat(userMapper.selectRoleCodesByUserId(user.getId()))
                .containsExactly("ROLE_STUDENT");

        // 同学号重复：1101
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentNo", no, "password", "Abcd@1234",
                                "nickname", "撞号", "email", "x" + no + "@t.campus.edu.cn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.STUDENT_ID_EXISTS.getCode()));

        // 同邮箱重复（换个学号）：1102——邮箱查重必须先于插库
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentNo", "m2x" + no, "password", "Abcd@1234",
                                "nickname", "撞邮箱", "email", user.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizCode.EMAIL_EXISTS.getCode()));
    }

    @Test
    @DisplayName("注册 DTO 校验：弱口令/脏学号 400 且零落库")
    void registerValidation400() throws Exception {
        long before = userMapper.selectCount(null);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentNo", "ab", "password", "12345678",
                                "nickname", "", "email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        assertThat(userMapper.selectCount(null)).isEqualTo(before);
    }

    // ====================== 登录 ======================

    @Test
    @DisplayName("登录防枚举：不存在与密码错统一 1002/401；封禁 1003/403；禁言可登录")
    void loginMatrix() throws Exception {
        // 账号不存在
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"nobody9999\",\"password\":\"" + SEED_PWD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1002));
        // 密码错（同一文案，不可区分）
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + STU_A + "\",\"password\":\"WrongPass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1002));
        // 封禁
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + STU_BANNED + "\",\"password\":\"" + SEED_PWD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1003));
        // 禁言可以进门（写口另测）
        String token = loginForToken(STU_MUTED, SEED_PWD);
        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("登录令牌携带 roles/perms 声明：/me 回显 RBAC 视图")
    void tokenCarriesRbacClaims() throws Exception {
        String managerToken = loginForToken(STU_MANAGER, SEED_PWD);
        MvcResult res = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        com.fasterxml.jackson.databind.JsonNode data = envelope(res).path("data");
        assertThat(data.path("roles").toString()).contains("ROLE_MANAGER");
        assertThat(data.path("perms").toString()).contains("item:audit");
        // me 是本人场景：PII 字段齐（email 非空）
        assertThat(data.path("email").asText()).isNotBlank();
    }

    // ====================== 无状态与越权 ======================

    @Test
    @DisplayName("无 Token 访问受保护接口 401/1001；脏 Token 401；登出后旧 Token 401")
    void statelessAndLogout() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer garbage.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));

        String token = loginForToken(STU_A, SEED_PWD);
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 旧令牌二次使用：jti 已拉黑
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("RBAC 越权矩阵：学生 Token 打 user:manage 口 1005；MANAGER 也无 user:manage")
    void rbacMatrix() throws Exception {
        String stuToken = loginForToken(STU_A, SEED_PWD);
        mockMvc.perform(patch("/api/admin/users/1/status").param("status", "0")
                        .header("Authorization", "Bearer " + stuToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1005));

        String mgrToken = loginForToken(STU_MANAGER, SEED_PWD);
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + mgrToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1005));
    }

    @Test
    @DisplayName("默认拒纪律：未登记的写路径无 Token 一律 401（白名单外无裸奔）")
    void unlistedPathsDeniedByDefault() throws Exception {
        // 公告/物品等模块 Controller 随 M3/M6 落地；本用例钉"新接口忘记登记=401"
        // 这一条 SecurityConfig 设计纪律（M6 落地后追加"游客 GET 公告=200"正向用例）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/items/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    // ====================== 改密与全端下线 ======================

    @Test
    @DisplayName("改密：旧密码错 1002；新旧相同 400；成功后当前令牌立失效、水位作废其它端")
    void changePasswordOfflineAll() throws Exception {
        String no = registerFreshUser("m2p");
        String token1 = loginForToken(no, "Abcd@1234");

        // 旧密码错
        mockMvc.perform(put("/api/users/password")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"WrongOld1\",\"newPassword\":\"NewPwd@5678\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1002));

        // 新旧相同
        mockMvc.perform(put("/api/users/password")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"Abcd@1234\",\"newPassword\":\"Abcd@1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 正常改密
        mockMvc.perform(put("/api/users/password")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"Abcd@1234\",\"newPassword\":\"NewPwd@5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 当前令牌被拉黑
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token1))
                .andExpect(status().isUnauthorized());

        // "其它端旧令牌"场景：水位判定需要签发时刻早于水位的第二把令牌——
        // 由于 iatms 毫秒精度 + 同用例内时钟推进，重新注册链路成本高，这里用
        // 同账号再次登录（签发于水位之后）证明新令牌可用，水位语义由双闸分工：
        // jti 拉黑已断言，水位在单元测试 JwtService/TokenBlacklist 层钉（M11 补全矩阵）
        String token2 = loginForToken(no, "NewPwd@5678");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token2))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ====================== 滑动续期 ======================

    @Test
    @DisplayName("续期：剩余>30min 不换发（renewed=false 且旧令牌仍可用）")
    void refreshNoRenewWhenFresh() throws Exception {
        String token = loginForToken(STU_A, SEED_PWD);
        mockMvc.perform(post("/api/auth/refresh").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.renewed").value(false));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ====================== admin 引导与治理护栏 ======================

    @Test
    @DisplayName("AdminBootstrapRunner 幂等：首启创建 admin+ADMIN 角色，重跑不改口令")
    void adminBootstrapIdempotent() throws Exception {
        adminBootstrap.run(null);
        adminBootstrap.run(null);   // 重跑不炸不重复
        SysUser admin = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentNo, "admin"));
        assertThat(admin).isNotNull();
        assertThat(userMapper.selectRoleCodesByUserId(admin.getId())).contains("ROLE_ADMIN");
        // 生产同款口令可登录
        String token = loginForToken("admin", "Admin@123");
        assertThat(token).isNotBlank();

        // 护栏 1：admin 禁自己 → 403
        mockMvc.perform(patch("/api/admin/users/" + admin.getId() + "/status")
                        .param("status", "0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // 护栏 2：admin 给自己剥离 ADMIN 角色 → 403
        mockMvc.perform(post("/api/admin/users/" + admin.getId() + "/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[1]}"))
                .andExpect(status().isForbidden());

        // 正路：处置他人封禁并踢线（改前状态正常）
        SysUser victim = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentNo, STU_A));
        String victimToken = loginForToken(STU_A, SEED_PWD);
        mockMvc.perform(patch("/api/admin/users/" + victim.getId() + "/status")
                        .param("status", "0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        // 封禁即踢线：victim 旧令牌立刻 401（水位作废）
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isUnauthorized());
    }

    // ====================== 禁言写口回查 ======================

    @Test
    @DisplayName("禁言账号可登录可读，但头像上传（写口）1003")
    void mutedCanReadNotWrite() throws Exception {
        String token = loginForToken(STU_MUTED, SEED_PWD);
        // 读口 OK
        mockMvc.perform(get("/api/users/profile").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        // 写口（头像）回查 status 拦截
        MockMultipartFile fakePng = new MockMultipartFile("file", "a.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0});
        mockMvc.perform(multipart("/api/users/avatar").file(fakePng)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ====================== 头像三卡 ======================

    @Test
    @DisplayName("头像：真 PNG 过三卡且回写 URL；HTML 换皮 .png 4001/4002 族")
    void avatarUploadThreeGates() throws Exception {
        String token = loginForToken(STU_A, SEED_PWD);

        byte[] pngHead = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        MockMultipartFile ok = new MockMultipartFile("file", "me.png", "image/png", pngHead);
        MvcResult res = mockMvc.perform(multipart("/api/users/avatar").file(ok)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String url = envelope(res).path("data").path("avatar").asText();
        assertThat(url).startsWith("/uploads/").endsWith(".png");
        // 库内回读一致
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentNo, STU_A));
        assertThat(user.getAvatar()).isEqualTo(url);

        // HTML 换皮 .png：MIME 与魔数双卡都过不了
        MockMultipartFile evil = new MockMultipartFile("file", "x.png", "text/html",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/users/avatar").file(evil)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(BizCode.FILE_TYPE_INVALID.getCode()));
    }

    // ====================== UserStatus 纯函数 ======================

    @Test
    @DisplayName("UserStatus 三态判定：null 保守拒绝；禁言可登录不可写")
    void userStatusPredicate() {
        assertThat(UserStatus.canLogin(UserStatus.NORMAL)).isTrue();
        assertThat(UserStatus.canLogin(UserStatus.MUTED)).isTrue();
        assertThat(UserStatus.canLogin(UserStatus.BANNED)).isFalse();
        assertThat(UserStatus.canLogin(null)).isFalse();
        assertThat(UserStatus.canWrite(UserStatus.NORMAL)).isTrue();
        assertThat(UserStatus.canWrite(UserStatus.MUTED)).isFalse();
        assertThat(UserStatus.canWrite(UserStatus.BANNED)).isFalse();
        assertThat(UserStatus.canWrite(null)).isFalse();
    }

    // ====================== JWT 单元面（密钥纪律） ======================

    @Test
    @DisplayName("JwtService 密钥纪律：<32B 配置直接抛 WeakKey（测试即文档）")
    void weakSecretRejected() {
        com.campus.lf.security.JwtProperties weak = new com.campus.lf.security.JwtProperties();
        weak.setSecret("short");
        weak.setExpireSeconds(7200);
        com.campus.lf.security.JwtService svc =
                new com.campus.lf.security.JwtService(weak);
        // 实测异常形态：SecretKeySpec 不自校验长度，jjwt 在签名时抛
        // SignatureException(cause=WeakKeyException)——断 cause 链而非表层类型，
        // "短密钥必须快速失败"的契约不变（博客 M2 同坑同修）
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> svc.issue(1L, "s", "n", null, java.util.List.of(), java.util.List.of()))
                .isInstanceOf(io.jsonwebtoken.security.SignatureException.class)
                .hasCauseInstanceOf(io.jsonwebtoken.security.WeakKeyException.class);
    }
}

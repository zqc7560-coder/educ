package com.campus.lf.module.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.module.auth.dto.LoginDTO;
import com.campus.lf.module.auth.dto.RegisterDTO;
import com.campus.lf.module.auth.vo.LoginVO;
import com.campus.lf.module.user.UserStatus;
import com.campus.lf.module.user.entity.SysUser;
import com.campus.lf.module.user.entity.SysUserRole;
import com.campus.lf.module.user.mapper.SysUserMapper;
import com.campus.lf.module.user.mapper.SysUserRoleMapper;
import com.campus.lf.module.user.vo.UserVO;
import com.campus.lf.security.JwtProperties;
import com.campus.lf.security.JwtService;
import com.campus.lf.security.LoginUser;
import com.campus.lf.security.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务（M2 核心）—— 注册 / 登录 / 登出 / 滑动续期 / me。
 *
 * <p>登录防枚举：用户不存在与密码错误统一回 1002 "账号或密码错误"，
 * 攻击者无法通过响应差异探测学号是否存在；区分只在应用日志（warn 级带内部
 * result 语义），DB 不留痕——本系统 M1 定表时收敛掉 login_log 表（治理面
 * 审计聚焦管理动作，operation_log 覆盖），登录爆破留痕走日志文件，
 * 若答辩要求 DB 审计再随 M11 补表。
 *
 * <p>注册并发：先 count 查重给友好提示（1101/1102），插入时仍捕获
 * DuplicateKeyException 再转同码——"查后插"竞态窗口由 uk_student_no/uk_email
 * 唯一键兜底。
 *
 * <p>禁言（status=2）语义在本类只体现为"可登录"；写口拦截由各业务 Service
 * 回查 status 执法（UserStatus.canWrite），无状态令牌不承担状态实时性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 种子角色编号：ROLE_STUDENT=1（data.sql 显式主键约定，注册默认授予） */
    private static final long ROLE_STUDENT_ID = 1L;

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final TokenBlacklistService blacklistService;

    // ============================== 注册 ==============================

    /** 注册（POST /api/auth/register），返回新用户 id。 */
    @Transactional
    public Long register(RegisterDTO dto) {
        String studentNo = dto.getStudentNo().trim();
        String email = dto.getEmail().trim().toLowerCase();

        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentNo, studentNo)) > 0) {
            throw new BizException(BizCode.STUDENT_ID_EXISTS);
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email)) > 0) {
            throw new BizException(BizCode.EMAIL_EXISTS);
        }

        SysUser user = new SysUser();
        user.setStudentNo(studentNo);
        // 明文在这一行之后即不可恢复（安全红线：库里只有 BCrypt 散列）
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname().trim());
        user.setEmail(email);
        user.setStatus(UserStatus.NORMAL);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(isStudentNoTaken(studentNo)
                    ? BizCode.STUDENT_ID_EXISTS : BizCode.EMAIL_EXISTS);
        }

        SysUserRole ur = new SysUserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(ROLE_STUDENT_ID);
        userRoleMapper.insert(ur);
        return user.getId();
    }

    private boolean isStudentNoTaken(String studentNo) {
        return userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentNo, studentNo)) > 0;
    }

    // ============================== 登录 ==============================

    /** 登录：BCrypt 校验 → 聚合 RBAC → 签 JWT → 写登录快照 → 回写 last_login_at。 */
    public LoginVO login(LoginDTO dto) {
        String studentNo = dto.getStudentNo().trim();
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStudentNo, studentNo));

        if (user == null) {
            log.warn("登录失败 result=不存在 studentNo={}", studentNo);
            throw new BizException(BizCode.LOGIN_FAILED, BizCode.LOGIN_FAILED.getMessage(), 401);
        }
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("登录失败 result=密码错 uid={}", user.getId());
            throw new BizException(BizCode.LOGIN_FAILED, BizCode.LOGIN_FAILED.getMessage(), 401);
        }
        // 封禁拒进门（禁言可登录——见 UserStatus 类注释）
        if (!UserStatus.canLogin(user.getStatus())) {
            log.warn("登录失败 result=封禁 uid={}", user.getId());
            throw new BizException(BizCode.ACCOUNT_DISABLED, BizCode.ACCOUNT_DISABLED.getMessage(), 403);
        }

        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        List<String> perms = userMapper.selectPermCodesByUserId(user.getId());
        // subject 用学号（日志可读）；roles/perms 进声明 = 每请求零查库的无状态认证基础
        String token = jwtService.issue(user.getId(), user.getStudentNo(),
                user.getNickname(), user.getAvatar(), roles, perms);

        // 登录快照（踢人水位寄生于此）；写失败仅告警不阻断登录（Redis 降级放行口径）
        try {
            blacklistService.writeLoginSnapshot(user.getId(), user.getStudentNo(),
                    jwtProperties.getExpireSeconds());
        } catch (Exception e) {
            log.warn("登录快照写入失败（Redis 降级）uid={}：{}", user.getId(), e.getMessage());
        }

        SysUser touch = new SysUser();
        touch.setId(user.getId());
        touch.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(touch);

        return LoginVO.builder()
                .token(token)
                .expiresIn(jwtProperties.getExpireSeconds())
                .user(UserVO.builder()
                        .id(user.getId())
                        .studentNo(user.getStudentNo())
                        .nickname(user.getNickname())
                        .avatar(user.getAvatar())
                        .roles(roles)
                        .perms(perms)
                        .build())
                .build();
    }

    // ============================== 登出 ==============================

    /**
     * 登出：拉黑当前会话 jti + 清登录快照。
     * 幂等：重复登出只是重写同一个黑名单键。
     */
    public void logout(LoginUser loginUser) {
        blacklistService.blacklistJti(loginUser.getJti(), loginUser.getTokenRemainingSeconds());
        blacklistService.removeLoginSnapshot(loginUser.getUserId());
    }

    // ============================== 滑动续期 ==============================

    /**
     * 续期（POST /api/auth/refresh）：剩余 ≥30min 原令牌照用（返回 null）；
     * <30min 换发新令牌并把旧 jti 拉黑（旋转式刷新——不限制"临期才换"等于
     * 架空 2h 短 TTL，被盗令牌可永续续命）。
     */
    public LoginVO refresh(LoginUser loginUser) {
        if (loginUser.getTokenRemainingSeconds() > 30 * 60) {
            return null;
        }
        blacklistService.blacklistJti(loginUser.getJti(), loginUser.getTokenRemainingSeconds());
        String token = jwtService.issue(loginUser.getUserId(), loginUser.getUsername(),
                loginUser.getNickname(), loginUser.getAvatar(),
                loginUser.getRoles(), loginUser.getPerms());
        return LoginVO.builder()
                .token(token)
                .expiresIn(jwtProperties.getExpireSeconds())
                .user(toVO(loginUser))
                .build();
    }

    // ============================== me / 装配辅助 ==============================

    /** 当前登录用户信息（GET /api/auth/me）：roles/perms 取令牌声明，库行只补 PII 字段 */
    public UserVO me(LoginUser loginUser) {
        SysUser db = userMapper.selectById(loginUser.getUserId());
        if (db == null) {
            // 令牌合法但账号已被（逻辑）删除：按失效处理，前端跳重新登录
            throw new BizException(BizCode.UNAUTHORIZED, BizCode.UNAUTHORIZED.getMessage(), 401);
        }
        UserVO vo = toVO(loginUser);
        vo.setEmail(db.getEmail());
        vo.setPhone(db.getPhone());
        vo.setStatus(db.getStatus());
        return vo;
    }

    /** LoginUser → UserVO（roles/perms 取令牌声明；本人场景的库字段由调用方覆盖） */
    public UserVO toVO(LoginUser loginUser) {
        return UserVO.builder()
                .id(loginUser.getUserId())
                .studentNo(loginUser.getUsername())
                .nickname(loginUser.getNickname())
                .avatar(loginUser.getAvatar())
                .roles(loginUser.getRoles())
                .perms(loginUser.getPerms())
                .build();
    }

    /** 供改密/资料更新后重新聚合最新 RBAC 装配 VO */
    public UserVO assembleVO(SysUser user) {
        return UserVO.builder()
                .id(user.getId())
                .studentNo(user.getStudentNo())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .email(user.getEmail())
                .phone(user.getPhone())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .roles(userMapper.selectRoleCodesByUserId(user.getId()))
                .perms(userMapper.selectPermCodesByUserId(user.getId()))
                .build();
    }
}

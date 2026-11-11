package com.campus.lf.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.module.auth.service.AuthService;
import com.campus.lf.module.storage.service.ImageStorageService;
import com.campus.lf.module.user.UserStatus;
import com.campus.lf.module.user.dto.ChangePasswordDTO;
import com.campus.lf.module.user.dto.ProfileUpdateDTO;
import com.campus.lf.module.user.entity.SysUser;
import com.campus.lf.module.user.mapper.SysUserMapper;
import com.campus.lf.module.user.vo.UserVO;
import com.campus.lf.security.JwtProperties;
import com.campus.lf.security.LoginUser;
import com.campus.lf.security.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户自助服务（M2）：资料查询/修改、改密、头像上传。
 *
 * <p>越权三防的第三层在这里：Controller 只传"当前登录用户 id"（取自令牌，
 * 不可伪造），Service 一律 WHERE id=登录者 更新，不接受请求体里的任意 userId
 * 写操作——查看他人（?userId=）是只读且隐去 PII，修改永远只能改自己。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final AuthService authService;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final TokenBlacklistService blacklistService;
    private final JwtProperties jwtProperties;
    private final ImageStorageService storageService;

    /**
     * 查看资料（GET /api/users/profile?userId=，缺省为自己）。
     *
     * <p>隐私分级：查自己/管理端（user:manage）返回全量；查他人抹掉
     * email/phone/status/createdAt/lastLoginAt——studentNo 是登录身份锚点，
     * 对非本人同样抹除（防按学号撞库探测账号存在性）。
     */
    public UserVO getProfile(LoginUser me, Long targetUserId) {
        Long id = (targetUserId == null || targetUserId.equals(0L)) ? me.getUserId() : targetUserId;
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(BizCode.USER_NOT_FOUND, BizCode.USER_NOT_FOUND.getMessage(), 404);
        }
        boolean self = id.equals(me.getUserId());
        boolean admin = me.getPerms().contains("user:manage");
        if (self || admin) {
            return authService.assembleVO(user);
        }
        UserVO vo = authService.assembleVO(user);
        vo.setStudentNo(null);
        vo.setEmail(null);
        vo.setPhone(null);
        vo.setStatus(null);
        vo.setCreatedAt(null);
        vo.setLastLoginAt(null);
        return vo;
    }

    /** 修改资料（PUT /api/users/profile）：昵称/邮箱/电话。学号不可改（类注释口径）。 */
    @Transactional
    public UserVO updateProfile(LoginUser me, ProfileUpdateDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        Long dup = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email)
                .ne(SysUser::getId, me.getUserId()));   // 排除自己占用自己邮箱的误报
        if (dup > 0) {
            throw new BizException(BizCode.EMAIL_EXISTS);
        }
        SysUser update = new SysUser();
        update.setId(me.getUserId());
        update.setNickname(dto.getNickname().trim());
        update.setEmail(email);
        update.setPhone(dto.getPhone());
        try {
            userMapper.updateById(update);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BizException(BizCode.EMAIL_EXISTS);
        }
        return authService.assembleVO(userMapper.selectById(me.getUserId()));
    }

    /**
     * 修改密码（PUT /api/users/password）。
     *
     * <p>"改密后全端强制重登"的两级实现：
     * ① 当前会话 jti 拉黑（立刻失效）；
     * ② 用户级失效水位抬到当前时刻——该用户其它端上的旧令牌（签发时间早于水位）
     *    同样被 JwtAuthFilter 拒绝。注意：不要用文档字面的"DEL login:user:{uid}"，
     *    DEL 会让水位消失反而放行旧令牌，正确实现就是抬水位（博客毕设同坑同修）。
     * 顺序：先校验旧密码 → 落新散列 → 最后作废令牌（校验失败不动任何状态）。
     */
    @Transactional
    public void changePassword(LoginUser me, ChangePasswordDTO dto) {
        SysUser user = userMapper.selectById(me.getUserId());
        if (user == null) {
            throw new BizException(BizCode.UNAUTHORIZED, BizCode.UNAUTHORIZED.getMessage(), 401);
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BizException(BizCode.LOGIN_FAILED, "旧密码错误", 401);
        }
        if (passwordEncoder.matches(dto.getNewPassword(), user.getPassword())) {
            throw new BizException(BizCode.PARAM_ERROR, "新密码不能与当前密码相同");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userMapper.updateById(update);

        blacklistService.blacklistJti(me.getJti(), me.getTokenRemainingSeconds());
        blacklistService.invalidateAllTokens(user.getId(), jwtProperties.getExpireSeconds());
    }

    /** 头像上传（POST /api/users/avatar，multipart file）：三卡校验落盘后回写 URL。 */
    @Transactional
    public UserVO uploadAvatar(LoginUser me, MultipartFile file) {
        assertWritable(me.getUserId());
        String url = storageService.store(file, "avatar", me.getUserId());
        SysUser update = new SysUser();
        update.setId(me.getUserId());
        update.setAvatar(url);
        userMapper.updateById(update);
        return authService.assembleVO(userMapper.selectById(me.getUserId()));
    }

    /**
     * 禁言回查闸：JWT 无状态盲区（令牌签发后才被禁言的人仍持有合法令牌），
     * 一切写入口在落库前回查 status——1003/403 语义复用 ACCOUNT_DISABLED。
     */
    private void assertWritable(Long userId) {
        SysUser db = userMapper.selectById(userId);
        if (db == null || !UserStatus.canWrite(db.getStatus())) {
            throw new BizException(BizCode.ACCOUNT_DISABLED, "账号被限制发言", 403);
        }
    }
}

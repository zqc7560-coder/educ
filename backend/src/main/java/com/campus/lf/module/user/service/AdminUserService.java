package com.campus.lf.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.PageResult;
import com.campus.lf.module.auth.service.AuthService;
import com.campus.lf.module.user.UserStatus;
import com.campus.lf.module.user.entity.SysRole;
import com.campus.lf.module.user.entity.SysUser;
import com.campus.lf.module.user.entity.SysUserRole;
import com.campus.lf.module.user.mapper.SysRoleMapper;
import com.campus.lf.module.user.mapper.SysUserMapper;
import com.campus.lf.module.user.mapper.SysUserRoleMapper;
import com.campus.lf.module.user.vo.UserVO;
import com.campus.lf.security.JwtProperties;
import com.campus.lf.security.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 管理端用户治理服务（M2）—— GET /api/admin/users、PATCH status、POST roles
 * （权限点 user:manage，ADMIN 独占）。
 *
 * <p>安全护栏（写进设计注释防止后人"顺手优化"掉）：
 * <ul>
 *   <li>封禁即踢线：status=0 时抬该用户失效水位，所有端旧令牌立刻 401——
 *       否则被禁用户在 2h TTL 内仍是"合法登录"，禁用形同虚设（禁言 2 同理抬水位？
 *       否——禁言不禁读，旧令牌继续可浏览是预期语义，只有写口回查拦截）；</li>
 *   <li>禁止处置自己 / 禁止移除自己的 ADMIN 角色：单人课设系统一旦误操作就是
 *       全员锁死、再无管理员能进后台，属于"必须显式拒绝"的毁灭性操作。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** 治理口可写入的状态集合（三态；具体语义 UserStatus） */
    private static final Set<Integer> MANAGEABLE_STATUS = Set.of(
            UserStatus.NORMAL, UserStatus.BANNED, UserStatus.MUTED);

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final AuthService authService;
    private final TokenBlacklistService blacklistService;
    private final JwtProperties jwtProperties;
    private final com.campus.lf.module.admin.service.OperationLogService opLog;

    /**
     * 用户分页（?keyword&status&pageNum&pageSize）。
     * keyword 模糊匹配学号/昵称/邮箱；status 精确过滤（null=全部）。
     */
    public PageResult<UserVO> pageUsers(String keyword, Integer status, long pageNum, long pageSize) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .orderByDesc(SysUser::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(SysUser::getStudentNo, kw)
                    .or().like(SysUser::getNickname, kw)
                    .or().like(SysUser::getEmail, kw));
        }
        if (status != null) {
            qw.eq(SysUser::getStatus, status);
        }
        Page<SysUser> page = userMapper.selectPage(new Page<>(pageNum, pageSize), qw);

        // 逐行装配 VO 是 N+1（后台一页 ≤100 行量级可接受；性能问题归 M9 优化项，先正确后快）
        List<UserVO> list = page.getRecords().stream().map(authService::assembleVO).toList();
        return new PageResult<>(page.getTotal(), page.getPages(),
                page.getCurrent(), page.getSize(), list);
    }

    /**
     * 启用/封禁/禁言（PATCH /api/admin/users/{id}/status，1/0/2）。
     * 幂等：目标状态与当前一致直接返回。仅"封禁"方向踢线（见类注释护栏）。
     */
    @Transactional
    public void changeStatus(Long operatorId, Long targetUserId, Integer status) {
        if (status == null || !MANAGEABLE_STATUS.contains(status)) {
            throw new BizException(BizCode.PARAM_ERROR, "status 仅接受 1(正常)/0(封禁)/2(禁言)");
        }
        if (operatorId.equals(targetUserId)) {
            throw new BizException(BizCode.FORBIDDEN, "不能处置自己的账号", 403);
        }
        SysUser target = requireUser(targetUserId);
        if (target.getStatus().equals(status)) {
            return; // 幂等空操作
        }
        SysUser update = new SysUser();
        update.setId(targetUserId);
        update.setStatus(status);
        userMapper.updateById(update);

        if (status == UserStatus.BANNED) {
            blacklistService.invalidateAllTokens(targetUserId, jwtProperties.getExpireSeconds());
            log.info("管理员 {} 封禁用户 {}，其全部登录令牌已作废", operatorId, targetUserId);
        }
        opLog.record(operatorId, switch (status) {
            case UserStatus.BANNED -> com.campus.lf.module.admin.service.OperationLogService.USER_BAN;
            case UserStatus.MUTED -> com.campus.lf.module.admin.service.OperationLogService.USER_MUTE;
            default -> com.campus.lf.module.admin.service.OperationLogService.USER_UNBAN;
        }, "user:" + targetUserId, "status " + target.getStatus() + "→" + status);
    }

    /**
     * 分配角色（POST /api/admin/users/{id}/roles，body {roleIds:[...]}）。
     * 语义"整组覆盖"：关联表即删即建（重算覆盖最简单，实体注释同口径）。
     */
    @Transactional
    public void assignRoles(Long operatorId, Long targetUserId, List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new BizException(BizCode.PARAM_ERROR, "roleIds 不能为空");
        }
        List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
        if (roles.size() != roleIds.stream().distinct().count()) {
            throw new BizException(BizCode.PARAM_ERROR, "包含不存在的角色ID");
        }
        requireUser(targetUserId);

        boolean removeSelfAdmin = operatorId.equals(targetUserId)
                && roles.stream().noneMatch(r -> "ROLE_ADMIN".equals(r.getRoleCode()));
        if (removeSelfAdmin) {
            throw new BizException(BizCode.FORBIDDEN, "不能移除自己的管理员角色", 403);
        }

        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, targetUserId));
        for (SysRole role : roles) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(targetUserId);
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);
        }
        // 角色集写进令牌声明：变更后旧令牌的权限视图是"改前快照"——抬水位强制重登，
        // 宁可踢用户下线，也不放"权限漂移"的令牌过关
        blacklistService.invalidateAllTokens(targetUserId, jwtProperties.getExpireSeconds());
        opLog.record(operatorId, com.campus.lf.module.admin.service.OperationLogService.USER_ROLES,
                "user:" + targetUserId, "roleIds=" + roleIds);
    }

    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(BizCode.USER_NOT_FOUND, BizCode.USER_NOT_FOUND.getMessage(), 404);
        }
        return user;
    }
}

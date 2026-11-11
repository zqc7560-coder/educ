package com.campus.lf.module.user.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户信息出参 —— 登录响应 data.user / GET /api/auth/me / PUT profile / 管理端列表共用。
 *
 * <p>为什么不直接序列化 SysUser 实体：实体含 password（BCrypt 散列）与 deleted 等
 * 内部列，直接出参等于把密码散列发给客户端——即使"只是散列"也给了离线爆破的原料。
 * VO 是白名单：只放文档约定字段，从类型结构上杜绝敏感列泄露，
 * 不依赖 @JsonIgnore 这类"记得加注解"的软约束。
 *
 * <p>studentNo（学号）是登录凭据而非展示身份：查他人场景置 null（见 UserService
 * 隐私分级），列表场景仅 user:manage 可见全量。
 */
@Data
@Builder
public class UserVO {

    /** 用户 ID */
    private Long id;

    /** 学号（登录名；非本人查询置 null） */
    private String studentNo;

    /** 昵称（展示名） */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /** 邮箱：仅本人或管理端填充（PII，防批量抓取） */
    private String email;

    /** 联系电话：仅本人或管理端填充（PII） */
    private String phone;

    /** 账号状态 1正常/0封禁/2禁言：仅本人与管理端场景填充 */
    private Integer status;

    /** 注册时间：仅管理端用户列表场景填充 */
    private LocalDateTime createdAt;

    /** 最后登录时间：仅管理端场景填充 */
    private LocalDateTime lastLoginAt;

    /** 角色码列表（ROLE_STUDENT/ROLE_MANAGER/ROLE_ADMIN）：前端 isAdmin 与菜单渲染依据 */
    private List<String> roles;

    /** 权限点列表：前端按钮级显隐依据（后端 @PreAuthorize 才是真防线） */
    private List<String> perms;
}

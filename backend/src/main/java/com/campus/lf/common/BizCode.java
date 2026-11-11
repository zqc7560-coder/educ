package com.campus.lf.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码枚举 —— 与《开发方案.md》§6 接口契约的错误码分段一一对应。
 *
 * <p>分段设计（沿用博客毕设验证过的口径）：HTTP 状态码只表达粗粒度结果
 * （200/400/401/403/404/500），业务细分靠 code 十位区段，前端拦截器按区段
 * 做统一动作（如 1001/1004 跳登录页），两套语义互不干扰。
 * 新增错误码必须同步更新 docs/ 接口章节，保持契约一致。
 */
@Getter
@AllArgsConstructor
public enum BizCode {

    /** 成功：统一响应体的默认码 */
    SUCCESS(0, "success"),

    /** 参数校验失败：message 携带字段明细 */
    PARAM_ERROR(400, "参数校验失败"),

    /** 系统内部异常兜底：日志记全栈，响应不回传堆栈（防信息泄露） */
    SYSTEM_ERROR(500, "系统内部异常"),

    // ---------- 认证 / 授权（1xxx） ----------
    /** 未认证或 Token 失效/已登出（HTTP 401） */
    UNAUTHORIZED(1001, "未认证或令牌失效"),
    /** 用户名或密码错误：不区分"用户不存在"，防账号枚举（HTTP 401） */
    LOGIN_FAILED(1002, "账号或密码错误"),
    /** 账号被禁用/封禁（HTTP 403） */
    ACCOUNT_DISABLED(1003, "账号已被禁用"),
    /** Token 过期，前端据此静默刷新或跳登录（HTTP 401） */
    TOKEN_EXPIRED(1004, "Token 已过期"),
    /** RBAC / 属主校验失败（HTTP 403） */
    FORBIDDEN(1005, "无权限访问该资源"),

    // ---------- 用户（11xx） ----------
    STUDENT_ID_EXISTS(1101, "学号已注册"),
    EMAIL_EXISTS(1102, "邮箱已被注册"),
    USER_NOT_FOUND(1103, "用户不存在"),
    NICKNAME_EXISTS(1104, "昵称已被占用"),

    // ---------- 物品（20xx~21xx） ----------
    ITEM_NOT_FOUND(2001, "物品信息不存在或已删除"),
    ITEM_NOT_OWNER(2002, "无权操作他人物品信息"),
    /** FOUND 件发布必须携带核验问题——认领流程的地基约束 */
    VERIFY_QUESTION_REQUIRED(2003, "拾物发布必须设置物主核验问题"),
    /** 乐观锁冲突：编辑/状态流转并发防覆盖 */
    VERSION_CONFLICT(2004, "内容已被更新，请刷新后重试"),
    ITEM_STATUS_INVALID(2005, "当前状态不允许该操作"),
    CATEGORY_NOT_FOUND(2101, "分类不存在"),
    AREA_NOT_FOUND(2102, "校区或楼栋不存在"),

    // ---------- 认领（30xx） ----------
    CLAIM_NOT_FOUND(3001, "认领申请不存在"),
    CLAIM_STATUS_INVALID(3002, "认领单当前状态不允许该操作"),
    CLAIM_DUPLICATE(3003, "您已对该物品提交过认领申请"),
    /** 确认归还竞态：先到先得，晚者见 3004（乐观锁失败的认领侧语义） */
    CLAIM_ALREADY_SETTLED(3004, "该物品已被确认归还"),
    CLAIM_NOT_PARTICIPANT(3005, "仅认领申请人或物品发布者可操作"),

    // ---------- 文件（40xx） ----------
    FILE_TYPE_INVALID(4001, "文件类型不合法"),
    FILE_TOO_LARGE(4002, "文件超过 5MB 限制"),

    // ---------- 论坛（50xx） ----------
    POST_NOT_FOUND(5001, "帖子不存在或已删除"),
    /** 两级封顶 + 同帖校验 + 幽灵父：三查合一的回复侧约束码 */
    REPLY_PARENT_INVALID(5002, "父回复不存在或层级超限"),
    BOARD_NOT_FOUND(5003, "板块不存在"),
    POST_NOT_OWNER(5004, "无权操作他人帖子"),
    REPORT_DUPLICATE(5005, "您已举报过该内容"),

    // ---------- 公告（60xx） ----------
    ANNOUNCEMENT_NOT_FOUND(6001, "公告不存在"),
    ANNOUNCEMENT_STATUS_INVALID(6002, "公告状态流转非法");

    /** 业务码 */
    private final int code;
    /** 默认中文提示（可直接展示） */
    private final String message;
}

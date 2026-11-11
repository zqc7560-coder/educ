package com.campus.lf.module.notice;

/**
 * 站内消息 biz_type 常量（schema 注释九类口径的代码事实源）。
 *
 * <p>前端按类型渲染图标/跳转路由：ITEM_AUDIT→物品详情、CLAIM_*→我的认领、
 * REPLY/LIKE→帖子、ANNOUNCE→公告、SYSTEM→通用。新增类型必须同时改这里
 * 与前端映射表，禁止业务代码裸传字符串（大小写漂移会让红点分桶失效）。
 */
public final class NotifyType {

    /** 物品审核结果（通过/驳回） */
    public static final String ITEM_AUDIT = "ITEM_AUDIT";
    /** 有人申请认领我的物品 */
    public static final String CLAIM_NEW = "CLAIM_NEW";
    /** 我的认领申请有了结果（通过/拒绝/关闭） */
    public static final String CLAIM_RESULT = "CLAIM_RESULT";
    /** 发布者确认归还 */
    public static final String RETURN_CONFIRM = "RETURN_CONFIRM";
    /** 失物↔招领匹配推荐 */
    public static final String MATCH = "MATCH";
    /** 论坛回复/@我 */
    public static final String REPLY = "REPLY";
    /** 帖子被赞 */
    public static final String LIKE = "LIKE";
    /** 帖子审核结果 */
    public static final String POST_AUDIT = "POST_AUDIT";
    /** 公告发布/系统广播 */
    public static final String SYSTEM = "SYSTEM";

    private NotifyType() {
    }
}

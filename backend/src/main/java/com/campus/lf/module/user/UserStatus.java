package com.campus.lf.module.user;

/**
 * 账号状态判定纯函数（M2）—— sys_user.status 三态的唯一执法口径。
 *
 * <p>语义分离（本系统比博客毕设多想了一步的场景）：
 * <ul>
 *   <li>0 封禁：登录入口即拒（1003）——人不许进门；</li>
 *   <li>2 禁言：可以登录、可以浏览，但一切写入口（发布/认领/发帖/回复）
 *       必须回查拦截——处分是"禁言"不是"监禁"。</li>
 * </ul>
 * 禁言为什么必须"写口回查"而不只靠 JWT：JWT 无状态，状态变更不进令牌声明，
 * 已登录的禁言者令牌在 TTL 内仍验签通过——唯一的可靠执法点是写操作时
 * selectById 回查 status（博客 M5 同款决策，认领/发帖/发布全部复用此闸）。
 */
public final class UserStatus {

    public static final int NORMAL = 1;
    public static final int BANNED = 0;
    public static final int MUTED = 2;

    private UserStatus() {
    }

    /** 登录闸门：仅封禁拒进门；禁言/正常放行（禁言由写口闸门处理） */
    public static boolean canLogin(Integer status) {
        return status != null && status != BANNED;
    }

    /** 写口闸门：正常才允许发布/认领/发帖/回复/申请仲裁 */
    public static boolean canWrite(Integer status) {
        return status != null && status == NORMAL;
    }
}

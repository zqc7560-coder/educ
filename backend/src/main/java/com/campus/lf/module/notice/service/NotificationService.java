package com.campus.lf.module.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.PageResult;
import com.campus.lf.module.notice.entity.Notification;
import com.campus.lf.module.notice.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内消息服务（M7）：统一投递口 + 收件箱查询 + 未读红点 + 已读标记。
 *
 * <p>设计口径（与 M4/M5 的散落 notify 收敛为一个事实源）：
 * <ul>
 *   <li><b>消息不可删</b>：read_flag 只翻 0→1，无删除接口——历史留底，
 *       红点计数永远等于真实未投递反馈量（审计视角：管理员可查任何人收件箱）；</li>
 *   <li><b>投递尽力而为</b>：notify() 内部吞异常只记日志——通知是旁路增强，
 *       绝不能因为消息表写失败回滚掉认领/审核主业务事务；</li>
 *   <li><b>全员群发</b>（notice:send）分批 insert，避免百万级 IN——毕设规模
 *       直接 select 活跃用户 id 再逐条，量大时换 insertSelect。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;

    /** 统一投递口：业务方只传语义四件套；异常吞噬保主事务（见类注释） */
    public void push(Long userId, String bizType, Long bizId, String title, String content) {
        if (userId == null) {
            return;
        }
        try {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setBizType(bizType);
            n.setBizId(bizId);
            n.setTitle(title);
            n.setContent(clip(content));
            n.setReadFlag(0);
            n.setCreatedAt(LocalDateTime.now());
            n.setDeleted(0);
            notificationMapper.insert(n);
        } catch (Exception e) {
            // 旁路失败不影响主流程（认领已落库、通知没发出去，是可容忍降级）
            log.warn("站内消息投递失败 uid={} type={} : {}", userId, bizType, e.getMessage());
        }
    }

    /** 收件箱分页（bizType 可选过滤；onlyUnread=true 只看未读） */
    public PageResult<Notification> pageMine(Long userId, String bizType, boolean onlyUnread,
                                             long pageNum, long pageSize) {
        LambdaQueryWrapper<Notification> w = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getId);
        if (bizType != null && !bizType.isBlank()) {
            w.eq(Notification::getBizType, bizType);
        }
        if (onlyUnread) {
            w.eq(Notification::getReadFlag, 0);
        }
        Page<Notification> page = notificationMapper.selectPage(new Page<>(pageNum, pageSize), w);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                page.getRecords());
    }

    /** 未读数（顶栏红点；轮询口，走 user_id+read_flag 组合条件） */
    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId).eq(Notification::getReadFlag, 0));
    }

    /** 标记单条已读（越权防护：where 带 user_id，改别人的消息=0 行=静默不存在） */
    @Transactional
    public void markRead(Long userId, Long id) {
        int updated = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, 0)
                .set(Notification::getReadFlag, 1));
        if (updated == 0) {
            // 已读过或不属于你：语义上都无需报错，红点幂等
            log.debug("markRead no-op id={} uid={}", id, userId);
        }
    }

    /** 全部已读 */
    @Transactional
    public int markAllRead(Long userId) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, 0)
                .set(Notification::getReadFlag, 1));
    }

    /** 管理员群发（notice:send；返回触达人数） */
    @Transactional
    public int broadcast(List<Long> userIds, String title, String content) {
        int sent = 0;
        for (Long uid : userIds) {
            push(uid, com.campus.lf.module.notice.NotifyType.SYSTEM, null, title, content);
            sent++;
        }
        log.info("群发通知 {} 人：{}", sent, title);
        return sent;
    }

    private String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 497) + "…";
    }

    /** 业务闸门：无权限调用群发口的兜底（Controller 层 hasPerm 之外的第二道） */
    public void assertSendPerm(com.campus.lf.security.LoginUser me) {
        if (me == null || !me.getPerms().contains("notice:send")) {
            throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
        }
    }
}

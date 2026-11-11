package com.campus.lf.module.notice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.PageResult;
import com.campus.lf.common.Result;
import com.campus.lf.module.notice.entity.Notification;
import com.campus.lf.module.notice.service.NotificationService;
import com.campus.lf.module.user.entity.SysUser;
import com.campus.lf.module.user.mapper.SysUserMapper;
import com.campus.lf.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 站内消息接口（M7）：收件箱/未读红点/已读标记 + 管理员群发。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final SysUserMapper userMapper;

    /** 我的消息（分页；bizType/onlyUnread 可选过滤） */
    @GetMapping("/notifications/mine")
    public Result<PageResult<Notification>> mine(@AuthenticationPrincipal LoginUser me,
                                                 @RequestParam(required = false) String bizType,
                                                 @RequestParam(defaultValue = "false") boolean onlyUnread,
                                                 @RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "10") long pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > 50) {
            return Result.fail(com.campus.lf.common.BizCode.PARAM_ERROR);
        }
        return Result.ok(notificationService.pageMine(me.getUserId(), bizType, onlyUnread, pageNum, pageSize));
    }

    /** 未读数（顶栏红点轮询口——响应只有一个数，前端 30s 拉一次足够） */
    @GetMapping("/notifications/unread-count")
    public Result<Map<String, Long>> unread(@AuthenticationPrincipal LoginUser me) {
        return Result.ok(Map.of("count", notificationService.unreadCount(me.getUserId())));
    }

    /** 单条已读（越权静默 no-op，见 service） */
    @PostMapping("/notifications/{id}/read")
    public Result<Void> read(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        notificationService.markRead(me.getUserId(), id);
        return Result.ok();
    }

    /** 全部已读（返回本次翻牌数，前端据此清红点） */
    @PostMapping("/notifications/read-all")
    public Result<Map<String, Integer>> readAll(@AuthenticationPrincipal LoginUser me) {
        return Result.ok(Map.of("updated", notificationService.markAllRead(me.getUserId())));
    }

    /** 管理员群发（notice:send）：body {title, content, userIds?}——不给 userIds 发全体正常状态用户 */
    @PostMapping("/admin/notifications/broadcast")
    public Result<Map<String, Integer>> broadcast(@AuthenticationPrincipal LoginUser me,
                                                  @RequestBody Map<String, Object> body) {
        notificationService.assertSendPerm(me);
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            return Result.fail(com.campus.lf.common.BizCode.PARAM_ERROR);
        }
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("userIds");
        List<Long> targets;
        if (rawIds != null && !rawIds.isEmpty()) {
            targets = rawIds.stream().map(o -> ((Number) o).longValue()).toList();
        } else {
            targets = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getStatus, 1))
                    .stream().map(SysUser::getId).toList();
        }
        return Result.ok(Map.of("sent", notificationService.broadcast(targets,
                title.trim(), content.trim())));
    }
}

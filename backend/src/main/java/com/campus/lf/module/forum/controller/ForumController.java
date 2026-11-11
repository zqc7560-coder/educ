package com.campus.lf.module.forum.controller;

import com.campus.lf.common.PageResult;
import com.campus.lf.common.Result;
import com.campus.lf.module.forum.dto.PostSaveDTO;
import com.campus.lf.module.forum.dto.ReplyDTO;
import com.campus.lf.module.forum.service.ForumService;
import com.campus.lf.module.forum.vo.PostVO;
import com.campus.lf.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 论坛接口（M5）。读口允许匿名（@AuthenticationPrincipal 未登录注入 null，
 * service 按 null 走公开视角）；写口 SecurityConfig 已强制登录。
 */
@RestController
@RequestMapping("/api/forum")
@RequiredArgsConstructor
public class ForumController {

    private final ForumService forumService;

    // ---------- 帖子 ----------

    @GetMapping("/posts")
    public Result<PageResult<PostVO>> list(@AuthenticationPrincipal LoginUser me,
                                           @RequestParam(required = false) Long boardId,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(defaultValue = "false") boolean mine,
                                           @RequestParam(defaultValue = "new") String sort,
                                           @RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > 50) {
            return Result.fail(com.campus.lf.common.BizCode.PARAM_ERROR);
        }
        return Result.ok(forumService.pagePosts(me, boardId, keyword, status, mine, sort, pageNum, pageSize));
    }

    @GetMapping("/posts/{id}")
    public Result<PostVO> detail(@AuthenticationPrincipal LoginUser me,
                                 @PathVariable Long id) {
        return Result.ok(forumService.detail(me, id));
    }

    @PostMapping("/posts")
    public Result<PostVO> create(@AuthenticationPrincipal LoginUser me,
                                 @Valid @RequestBody PostSaveDTO dto) {
        return Result.ok(forumService.create(me, dto));
    }

    @PutMapping("/posts/{id}")
    public Result<Void> update(@AuthenticationPrincipal LoginUser me,
                               @PathVariable Long id,
                               @Valid @RequestBody PostSaveDTO dto) {
        forumService.update(me, id, dto);
        return Result.ok();
    }

    @DeleteMapping("/posts/{id}")
    public Result<Void> delete(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        forumService.deleteByAuthor(me, id);
        return Result.ok();
    }

    /** 治理流转（审核/下架/恢复合一）：body {target, reason} */
    @PatchMapping("/posts/{id}/status")
    public Result<Void> transition(@AuthenticationPrincipal LoginUser me,
                                   @PathVariable Long id,
                                   @RequestParam int target,
                                   @RequestBody(required = false) Map<String, String> body) {
        forumService.transition(me, id, target, body == null ? null : body.get("reason"));
        return Result.ok();
    }

    @PostMapping("/posts/{id}/top")
    public Result<Void> toggleTop(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        forumService.toggleTop(me, id);
        return Result.ok();
    }

    // ---------- 回复 ----------

    @GetMapping("/posts/{id}/replies")
    public Result<List<Map<String, Object>>> replies(@AuthenticationPrincipal LoginUser me,
                                                     @PathVariable Long id) {
        return Result.ok(forumService.replyTree(me, id));
    }

    @PostMapping("/posts/{id}/replies")
    public Result<Map<String, Long>> reply(@AuthenticationPrincipal LoginUser me,
                                           @PathVariable Long id,
                                           @Valid @RequestBody ReplyDTO dto) {
        return Result.ok(Map.of("id", forumService.reply(me, id, dto)));
    }

    @DeleteMapping("/replies/{replyId}")
    public Result<Void> deleteReply(@AuthenticationPrincipal LoginUser me, @PathVariable Long replyId) {
        forumService.deleteReply(me, replyId);
        return Result.ok();
    }

    // ---------- 点赞 / 举报 ----------

    @PostMapping("/posts/{id}/like")
    public Result<Map<String, Boolean>> like(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        return Result.ok(Map.of("liked", forumService.like(me, id)));
    }

    @DeleteMapping("/posts/{id}/like")
    public Result<Map<String, Boolean>> unlike(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        return Result.ok(Map.of("liked", forumService.unlike(me, id)));
    }

    /** 举报（P1 最小实现：转回待审进治理队列） */
    @PostMapping("/posts/{id}/report")
    public Result<Void> report(@AuthenticationPrincipal LoginUser me,
                               @PathVariable Long id,
                               @RequestBody(required = false) Map<String, String> body) {
        forumService.report(me, id, body == null ? null : body.get("reason"));
        return Result.ok();
    }
}

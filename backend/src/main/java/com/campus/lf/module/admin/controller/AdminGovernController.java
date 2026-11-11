package com.campus.lf.module.admin.controller;

import com.campus.lf.common.PageResult;
import com.campus.lf.common.Result;
import com.campus.lf.module.admin.entity.OperationLog;
import com.campus.lf.module.admin.entity.SensitiveWord;
import com.campus.lf.module.admin.service.AdminGovernService;
import com.campus.lf.module.admin.service.StatsService;
import com.campus.lf.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端治理接口（M8）：审核队列 / 回复处置 / 词库 / 参数 / 看板 / 操作日志。
 *
 * <p>权限全部在 service 层 assert*（细粒度到权限点），Security 链只保证 /api/admin/**
 * 已登录。用户治理口在 user 模块的 AdminUserController、公告管理在 announce 模块——
 * 按领域归属而非"admin"前缀集中放置（台账 M8 交付项横跨这些既有口）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminGovernController {

    private final AdminGovernService governService;
    private final StatsService statsService;

    // ---------- 审核队列 ----------

    /** 三队列合一（只读聚合；处置回各业务域状态机口） */
    @GetMapping("/audit-queue")
    public Result<Map<String, Object>> auditQueue(@AuthenticationPrincipal LoginUser me,
                                                  @RequestParam(defaultValue = "10") int size) {
        return Result.ok(governService.auditQueue(me, size));
    }

    /** 回复待审分页 */
    @GetMapping("/replies/pending")
    public Result<PageResult<Map<String, Object>>> pendingReplies(@AuthenticationPrincipal LoginUser me,
                                                                  @RequestParam(defaultValue = "1") long pageNum,
                                                                  @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(governService.pagePendingReplies(pageNum, pageSize));
    }

    /** 回复处置：body {target:1|2, reason} */
    @PostMapping("/replies/{id}/handle")
    public Result<Void> handleReply(@AuthenticationPrincipal LoginUser me,
                                    @PathVariable Long id,
                                    @RequestBody Map<String, Object> body) {
        Integer target = body.get("target") == null ? null : ((Number) body.get("target")).intValue();
        if (target == null) {
            return Result.fail(com.campus.lf.common.BizCode.PARAM_ERROR);
        }
        governService.handleReply(me, id, target, (String) body.get("reason"));
        return Result.ok();
    }

    // ---------- 敏感词库 ----------

    @GetMapping("/words")
    public Result<PageResult<SensitiveWord>> words(@AuthenticationPrincipal LoginUser me,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(governService.pageWords(me, keyword, pageNum, pageSize));
    }

    @PostMapping("/words")
    public Result<Map<String, Long>> addWord(@AuthenticationPrincipal LoginUser me,
                                             @RequestBody Map<String, String> body) {
        return Result.ok(Map.of("id", governService.addWord(me, body.get("word"))));
    }

    @DeleteMapping("/words/{id}")
    public Result<Void> deleteWord(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        governService.deleteWord(me, id);
        return Result.ok();
    }

    // ---------- 系统参数 ----------

    @GetMapping("/configs")
    public Result<List<Map<String, Object>>> configs(@AuthenticationPrincipal LoginUser me) {
        return Result.ok(governService.listConfigs(me));
    }

    /** 改参数：body {value}（类型闸见 service） */
    @PutMapping("/configs/{key}")
    public Result<Void> updateConfig(@AuthenticationPrincipal LoginUser me,
                                     @PathVariable String key,
                                     @RequestBody Map<String, String> body) {
        governService.updateConfig(me, key, body.get("value"));
        return Result.ok();
    }

    // ---------- 数据看板 ----------

    @GetMapping("/stats/overview")
    public Result<Map<String, Object>> overview(@AuthenticationPrincipal LoginUser me) {
        return Result.ok(statsService.overview(me));
    }

    // ---------- 操作日志 ----------

    @GetMapping("/oplogs")
    public Result<PageResult<OperationLog>> opLogs(@AuthenticationPrincipal LoginUser me,
                                                   @RequestParam(required = false) String action,
                                                   @RequestParam(required = false) String target,
                                                   @RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(statsService.pageLogs(me, action, target, pageNum, pageSize));
    }
}

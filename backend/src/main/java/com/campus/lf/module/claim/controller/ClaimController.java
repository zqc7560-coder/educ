package com.campus.lf.module.claim.controller;

import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.PageResult;
import com.campus.lf.common.Result;
import com.campus.lf.module.claim.dto.ClaimApplyDTO;
import com.campus.lf.module.claim.service.ClaimService;
import com.campus.lf.module.claim.vo.ClaimVO;
import com.campus.lf.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 认领接口（M4）—— 全部登录态；权限判定在 service 层（发布者/申请人/仲裁员三视角），
 * 这里只做参数绑定与身份透传。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService claimService;

    /** 提交认领申请（仅 FOUND 已发布件；自己发布的不可认领） */
    @PostMapping("/items/{id}/claims")
    public Result<Map<String, Long>> apply(@AuthenticationPrincipal LoginUser me,
                                           @PathVariable Long id,
                                           @Valid @RequestBody ClaimApplyDTO dto) {
        return Result.ok(Map.of("id", claimService.apply(me, id, dto)));
    }

    /** 某物品的认领单列表（发布者核验视图 / 仲裁管理员可用；answer 按视角脱敏） */
    @GetMapping("/items/{id}/claims")
    public Result<List<ClaimVO>> listForItem(@AuthenticationPrincipal LoginUser me,
                                             @PathVariable Long id) {
        return Result.ok(claimService.listForItem(me, id));
    }

    /** 我发出的认领（申请人视角分页） */
    @GetMapping("/claims/mine")
    public Result<PageResult<ClaimVO>> mine(@AuthenticationPrincipal LoginUser me,
                                            @RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(claimService.pageMine(me, pageNum, pageSize));
    }

    /** 通过核验（发布者；body 可选 {reason}） */
    @PostMapping("/claims/{id}/verify")
    public Result<Void> verifyPass(@AuthenticationPrincipal LoginUser me,
                                   @PathVariable Long id,
                                   @RequestBody(required = false) Map<String, String> body) {
        claimService.verifyPass(me, id, body == null ? null : body.get("reason"));
        return Result.ok();
    }

    /** 拒绝核验（发布者；reason 必填） */
    @PostMapping("/claims/{id}/reject")
    public Result<Void> verifyReject(@AuthenticationPrincipal LoginUser me,
                                     @PathVariable Long id,
                                     @RequestBody Map<String, String> body) {
        claimService.verifyReject(me, id, body.get("reason"));
        return Result.ok();
    }

    /** 确认归还（发布者；连带物品下架 + 其余在途单批量关闭） */
    @PostMapping("/claims/{id}/confirm")
    public Result<Void> confirm(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        claimService.confirmReturn(me, id);
        return Result.ok();
    }

    /** 申请仲裁（申请人或发布者任一方可发起） */
    @PostMapping("/claims/{id}/arbitrate-apply")
    public Result<Void> applyArbitration(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        claimService.applyArbitration(me, id);
        return Result.ok();
    }

    /** 仲裁裁决（claim:arbitrate；body {awardToClaimant:true/false, judgment}） */
    @PostMapping("/admin/claims/{id}/arbitrate")
    public Result<Void> arbitrate(@AuthenticationPrincipal LoginUser me,
                                  @PathVariable Long id,
                                  @RequestBody Map<String, Object> body) {
        boolean award = Boolean.TRUE.equals(body.get("awardToClaimant"));
        claimService.arbitrate(me, id, award, (String) body.get("judgment"));
        return Result.ok();
    }

    /** 待仲裁队列（claim:arbitrate） */
    @GetMapping("/admin/claims/arbitration")
    public Result<PageResult<ClaimVO>> arbitrationQueue(@AuthenticationPrincipal LoginUser me,
                                                        @RequestParam(defaultValue = "1") long pageNum,
                                                        @RequestParam(defaultValue = "10") long pageSize) {
        // 权限自查在 Controller：这是"读"，没有 service 写动作可以兜底——
        // 忘了拦就是全体登录用户能看仲裁队列（数据泄露级）
        if (!me.getPerms().contains("claim:arbitrate")) {
            throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
        }
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
            return Result.fail(BizCode.PARAM_ERROR);
        }
        return Result.ok(claimService.pageArbitration(pageNum, pageSize));
    }
}

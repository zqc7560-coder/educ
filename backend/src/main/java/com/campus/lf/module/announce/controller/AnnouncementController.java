package com.campus.lf.module.announce.controller;

import com.campus.lf.common.PageResult;
import com.campus.lf.common.Result;
import com.campus.lf.module.announce.dto.AnnouncementSaveDTO;
import com.campus.lf.module.announce.service.AnnouncementService;
import com.campus.lf.module.announce.vo.AnnouncementVO;
import com.campus.lf.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 公告接口（M6）：读者侧公开（白名单 GET 免登录），管理侧 /api/admin/** 显式权限点。
 *
 * <p>路径口径：读者口挂在已在白名单的 /api/announcements*；管理口独立前缀，
 * 未登录由 Security 链拦（authenticated），越权由 service.assertManage 拦（403/1005）。
 */
@RestController
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    // ---------- 读者侧（公开） ----------

    /** 公告列表（分页，窗口内已发布） */
    @GetMapping("/api/announcements")
    public Result<PageResult<AnnouncementVO>> list(@RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "10") long pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > 50) {
            return Result.ok(announcementService.pagePublic(1, 10));
        }
        return Result.ok(announcementService.pagePublic(pageNum, pageSize));
    }

    /** 首页横幅（置顶优先取最新一条；announcement_enabled=0 返回空数组） */
    @GetMapping("/api/announcements/active")
    public Result<List<AnnouncementVO>> active() {
        return Result.ok(announcementService.activeBanner());
    }

    /** 详情（浏览计数按访客去重；非窗口内已发布 404/6001） */
    @GetMapping("/api/announcements/{id}")
    public Result<AnnouncementVO> detail(@PathVariable Long id,
                                         @org.springframework.security.core.annotation.AuthenticationPrincipal
                                         com.campus.lf.security.LoginUser me,
                                         jakarta.servlet.http.HttpServletRequest request) {
        String viewerKey;
        if (me != null) {
            viewerKey = "u:" + me.getUserId();
        } else {
            String rip = request.getHeader("X-Real-IP");
            viewerKey = "ip:" + (rip == null || rip.isBlank() ? request.getRemoteAddr() : rip);
        }
        return Result.ok(announcementService.detail(id, viewerKey));
    }

    // ---------- 管理侧（announce:manage） ----------

    @PostMapping("/api/admin/announcements")
    public Result<Map<String, Long>> create(@AuthenticationPrincipal LoginUser me,
                                            @Valid @RequestBody AnnouncementSaveDTO dto) {
        return Result.ok(Map.of("id", announcementService.create(me, dto)));
    }

    @PutMapping("/api/admin/announcements/{id}")
    public Result<Void> update(@AuthenticationPrincipal LoginUser me,
                               @PathVariable Long id,
                               @Valid @RequestBody AnnouncementSaveDTO dto) {
        announcementService.update(me, id, dto);
        return Result.ok();
    }

    /** 状态动作口：body {target}（1=发布 2=下架）——编辑与发布分离，防"编辑即发布" */
    @PatchMapping("/api/admin/announcements/{id}/status")
    public Result<Void> transition(@AuthenticationPrincipal LoginUser me,
                                   @PathVariable Long id,
                                   @RequestBody Map<String, Integer> body) {
        Integer target = body.get("target");
        if (target == null) {
            return Result.fail(com.campus.lf.common.BizCode.PARAM_ERROR);
        }
        announcementService.transition(me, id, target);
        return Result.ok();
    }

    @PostMapping("/api/admin/announcements/{id}/top")
    public Result<Void> toggleTop(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        announcementService.toggleTop(me, id);
        return Result.ok();
    }

    @DeleteMapping("/api/admin/announcements/{id}")
    public Result<Void> delete(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        announcementService.delete(me, id);
        return Result.ok();
    }

    @GetMapping("/api/admin/announcements")
    public Result<PageResult<AnnouncementVO>> pageAdmin(@AuthenticationPrincipal LoginUser me,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(defaultValue = "1") long pageNum,
                                                        @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(announcementService.pageAdmin(me, status, pageNum, pageSize));
    }
}

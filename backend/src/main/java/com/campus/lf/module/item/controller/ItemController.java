package com.campus.lf.module.item.controller;

import com.campus.lf.common.PageResult;
import com.campus.lf.common.Result;
import com.campus.lf.module.item.dto.ItemSaveDTO;
import com.campus.lf.module.item.service.ItemService;
import com.campus.lf.module.item.vo.ItemVO;
import com.campus.lf.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 物品接口（M3）—— 检索/详情对游客开放（SecurityConfig GET 白名单），
 * 写口全部登录态；身份只取自令牌（@AuthenticationPrincipal），
 * 不接受请求参数里的 userId 写操作（越权三防第三层在 Service）。
 *
 * <p>mine=1 的特判：GET /api/items 白名单放行了该路径，匿名带 mine=1 必须
 * 显式 401——否则 Service 里 me=null 走"我的发布"查询会把全库当匿名可见
 * （博客 M3 实坑，同款防御在此复现）。
 */
@Tag(name = "物品", description = "失物/拾物发布与检索")
@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    /** GET /api/items：公开检索（type/categoryId/campusId/buildingId/keyword/from/to/sort/page） */
    @Operation(summary = "分页检索（仅已发布；keyword LIKE，sort=hot 热度序）")
    @GetMapping
    public Result<PageResult<ItemVO>> page(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long campusId,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @AuthenticationPrincipal LoginUser me) {
        if (mine) {
            if (me == null) {
                return Result.fail(com.campus.lf.common.BizCode.UNAUTHORIZED);
            }
            return Result.ok(itemService.pageMine(me, null, pageNum, pageSize));
        }
        return Result.ok(itemService.pageItems(type, categoryId, keyword, campusId, buildingId,
                from, to, sort, pageNum, pageSize));
    }

    /** GET /api/items/{id}：详情（浏览计数；contact 仅登录可见；非发布态按矩阵裁决） */
    @Operation(summary = "物品详情")
    @GetMapping("/{id}")
    public Result<ItemVO> detail(@PathVariable Long id, @AuthenticationPrincipal LoginUser me) {
        return Result.ok(itemService.detail(id, me));
    }

    /** POST /api/items（登录）：发布；FOUND 必带核验问题；审核开关定初始态 */
    @Operation(summary = "发布（LOST/FOUND）")
    @PostMapping
    public Result<Map<String, Object>> create(@AuthenticationPrincipal LoginUser me,
                                              @Valid @RequestBody ItemSaveDTO dto) {
        return Result.ok(itemService.create(me, dto));
    }

    /** PUT /api/items/{id}（登录，作者）：编辑（version 必填，冲突 2004；type 不可改） */
    @Operation(summary = "编辑（乐观锁）")
    @PutMapping("/{id}")
    public Result<Void> update(@AuthenticationPrincipal LoginUser me,
                               @PathVariable Long id,
                               @Valid @RequestBody ItemSaveDTO dto) {
        itemService.update(me, id, dto);
        return Result.ok();
    }

    /** PATCH /api/items/{id}/status?target=&reason=：下架(2，理由必填)/重上架(1)；审核流转同口（挂权限点判定在 Service） */
    @Operation(summary = "状态流转（作者自助 1⇄2；审核动作由治理权限放行）")
    @PatchMapping("/{id}/status")
    public Result<Void> changeStatus(@AuthenticationPrincipal LoginUser me,
                                     @PathVariable Long id,
                                     @RequestParam Integer target,
                                     @RequestParam(required = false) String reason) {
        itemService.changeStatus(me, id, target, reason);
        return Result.ok();
    }

    /** DELETE /api/items/{id}：逻辑删除（作者或治理口） */
    @Operation(summary = "删除（回收站语义，物理行保留）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        itemService.delete(me, id);
        return Result.ok();
    }
}

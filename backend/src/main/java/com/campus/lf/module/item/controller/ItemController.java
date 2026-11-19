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
    public Result<ItemVO> detail(@PathVariable Long id, @AuthenticationPrincipal LoginUser me,
                                 @RequestParam(defaultValue = "1") int pv,
                                 jakarta.servlet.http.HttpServletRequest request) {
        // 浏览去重身份：登录者按 uid；游客按真实来源 IP（nginx 已注入 X-Real-IP）
        String viewerKey;
        if (me != null) {
            viewerKey = "u:" + me.getUserId();
        } else {
            String rip = request.getHeader("X-Real-IP");
            viewerKey = "ip:" + (rip == null || rip.isBlank() ? request.getRemoteAddr() : rip);
        }
        return Result.ok(itemService.detail(id, me, viewerKey, pv != 0));
    }

    /**
     * GET /api/items/mine（登录）：我的发布全状态视图。
     * 精确段 /mine 在 Spring MVC 模式比较里优先于模板 /{id}（静态段胜出），
     * 但前端 M10 首测时曾撞出 500——那正是 /{id} 抢先把 "mine" 当 Long 转炸的
     * 教训：此路由缺失时 /{id} 会兜住它。补登记后必须钉一条集成断言（M10 测试）。
     */
    @Operation(summary = "我的发布（全状态对自己透明）")
    @GetMapping("/mine")
    public Result<PageResult<ItemVO>> mine(@AuthenticationPrincipal LoginUser me,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize) {
        // GET /api/items/* 在公开白名单里（游客可逛详情），mine 段必须自拦匿名——
        // 否则 me=null 进 service 直接 NPE（白名单前缀通配的固有代价，同 page 口 mine=1 分支）
        if (me == null) {
            return Result.fail(com.campus.lf.common.BizCode.UNAUTHORIZED);
        }
        return Result.ok(itemService.pageMine(me, status, pageNum, pageSize));
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

    /** PATCH /api/items/{id}/top：置顶/取消（item:top） */
    @Operation(summary = "置顶开关（治理）")
    @PatchMapping("/{id}/top")
    public Result<Void> toggleTop(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        itemService.toggleTop(me, id);
        return Result.ok();
    }

    /** GET /api/items/{id}/similar：详情页相似推荐 Top5（公开，仅已发布件有结果） */
    @Operation(summary = "相似物品推荐（同分类+时间窗+地点+文本重合度）")
    @GetMapping("/{id}/similar")
    public Result<java.util.List<java.util.Map<String, Object>>> similar(@PathVariable Long id) {
        return Result.ok(itemService.similar(id, 5));
    }

    /** DELETE /api/items/{id}：逻辑删除（作者或治理口） */
    @Operation(summary = "删除（回收站语义，物理行保留）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        itemService.delete(me, id);
        return Result.ok();
    }

    // ---------- 回收站（M10 管理员治理通道；/trash 精确段必须先于 /{id} 模板注册 ----
    // Spring MVC 的匹配是"精确优先"而非书写顺序，但历史 Spring 版本有顺序敏感性，
    // 显式把精确路径放前面是跨版本稳态写法） ----------

    @Operation(summary = "回收站分页（item:audit）")
    @GetMapping("/trash")
    public Result<PageResult<ItemVO>> trash(@AuthenticationPrincipal LoginUser me,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(itemService.pageTrash(me, keyword, pageNum, pageSize));
    }

    @Operation(summary = "复活（状态保持删除前原值，幂等护栏）")
    @PostMapping("/{id}/restore")
    public Result<Void> restore(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        itemService.restore(me, id);
        return Result.ok();
    }

    @Operation(summary = "彻底删除（物理删行，仅对回收站内物品生效）")
    @DeleteMapping("/{id}/purge")
    public Result<Void> purge(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
        itemService.purge(me, id);
        return Result.ok();
    }
}

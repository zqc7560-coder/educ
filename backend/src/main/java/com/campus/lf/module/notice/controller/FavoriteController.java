package com.campus.lf.module.notice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.common.Result;
import com.campus.lf.module.forum.entity.UserFavorite;
import com.campus.lf.module.forum.mapper.UserFavoriteMapper;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 收藏接口（M10 补齐后端缺口）：物品/帖子的收藏 toggle 与"我的收藏"列表。
 *
 * <p>uk(user_id,target_type,target_id) 幂等 toggle：已收藏再点=取消（delete），
 * 未收藏点=新增（insert，并发 DuplicateKey 视为已收藏静默成功——与论坛点赞同纪律）。
 * targetType 白名单 ITEM/POST：枚举闸防脏值把回收站/统计口径打穿。
 */
@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    public static final String TYPE_ITEM = "ITEM";
    public static final String TYPE_POST = "POST";
    /** 帖子已发布态（与 ForumService.STATUS_PUBLISHED 对齐；收藏闸口径=点赞/回复同款） */
    private static final int POST_STATUS_PUBLISHED = 1;

    private final UserFavoriteMapper favoriteMapper;
    private final LostItemMapper itemMapper;
    private final com.campus.lf.module.forum.mapper.ForumPostMapper postMapper;

    /** POST /api/favorites/toggle {targetType,targetId} → {favorited: 操作后状态} */
    @Operation(summary = "收藏/取消收藏（幂等 toggle）")
    @PostMapping("/toggle")
    public Result<Map<String, Object>> toggle(@AuthenticationPrincipal LoginUser me,
                                              @RequestBody Map<String, Object> body) {
        String targetType = String.valueOf(body.get("targetType"));
        Number targetIdNum = (Number) body.get("targetId");
        if ((!TYPE_ITEM.equals(targetType) && !TYPE_POST.equals(targetType)) || targetIdNum == null) {
            throw new BizException(BizCode.PARAM_ERROR, "targetType 仅 ITEM/POST，targetId 必填");
        }
        Long targetId = targetIdNum.longValue();
        // 目标存在性：收藏不存在的物品=孤儿键，列表渲染时反查空行
        if (TYPE_ITEM.equals(targetType) && itemMapper.selectById(targetId) == null) {
            throw new BizException(BizCode.ITEM_NOT_FOUND, BizCode.ITEM_NOT_FOUND.getMessage(), 404);
        }
        LambdaQueryWrapper<UserFavorite> w = new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, me.getUserId())
                .eq(UserFavorite::getTargetType, targetType)
                .eq(UserFavorite::getTargetId, targetId);
        UserFavorite existing = favoriteMapper.selectOne(w);
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            return Result.ok(Map.of("favorited", false));
        }
        // 新增收藏才过状态闸（取消收藏不受限）：未发布（待审/下架）的帖子不可收藏——
        // 与点赞(5001)/回复(5008)同口径，防"作者自己收藏待审帖养权重"与列表反查空行
        if (TYPE_POST.equals(targetType)) {
            com.campus.lf.module.forum.entity.ForumPost post = postMapper.selectById(targetId);
            if (post == null) {
                throw new BizException(BizCode.POST_NOT_FOUND, BizCode.POST_NOT_FOUND.getMessage(), 404);
            }
            if (post.getStatus() != POST_STATUS_PUBLISHED) {
                throw new BizException(BizCode.POST_STATUS_INVALID, "帖子未发布，暂不可收藏");
            }
        }
        UserFavorite f = new UserFavorite();
        f.setUserId(me.getUserId());
        f.setTargetType(targetType);
        f.setTargetId(targetId);
        f.setCreatedAt(LocalDateTime.now());
        try {
            favoriteMapper.insert(f);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发双击：另一线程已插入——幂等语义下静默按"已收藏"返回
        }
        return Result.ok(Map.of("favorited", true));
    }

    /** GET /api/favorites?type=ITEM|POST（缺省全部）：本用户收藏的 targetId 清单 */
    @Operation(summary = "我的收藏清单（id 列表，前端回查详情）")
    @GetMapping
    public Result<List<Map<String, Object>>> mine(@AuthenticationPrincipal LoginUser me,
                                                  @RequestParam(required = false) String type) {
        LambdaQueryWrapper<UserFavorite> w = new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, me.getUserId())
                .orderByDesc(UserFavorite::getId);
        if (type != null && !type.isBlank()) {
            w.eq(UserFavorite::getTargetType, type.trim().toUpperCase());
        }
        return Result.ok(favoriteMapper.selectList(w).stream().map(f -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("targetType", f.getTargetType());
            m.put("targetId", f.getTargetId());
            m.put("createdAt", f.getCreatedAt());
            return m;
        }).toList());
    }
}

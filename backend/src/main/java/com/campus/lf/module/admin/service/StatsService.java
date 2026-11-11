package com.campus.lf.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.common.PageResult;
import com.campus.lf.module.admin.entity.OperationLog;
import com.campus.lf.module.admin.mapper.OperationLogMapper;
import com.campus.lf.module.claim.mapper.ItemClaimMapper;
import com.campus.lf.module.forum.mapper.ForumPostMapper;
import com.campus.lf.module.forum.mapper.ForumReplyMapper;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.module.item.service.ItemService;
import com.campus.lf.module.notice.service.NotificationService;
import com.campus.lf.module.user.service.AdminUserService;
import com.campus.lf.security.LoginUser;
import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据看板（M8）：总量/找回率/分类分布/7 日趋势/论坛热度——全部聚合 SQL 直查，
 * 缓存留 M9（看板请求频率低，先保口径正确）。
 *
 * <p>找回率口径（答辩高频问题，写死在这里）：
 * 找回率 = 已归还下架件 / 全部 FOUND 件。分母用"所有招领发布"而非"已发布"，
 * 反映真实闭环效率；下架原因=已归还 的判定来自 M4 confirmReturn 写入的
 * offline_reason 字符串——弱契约，故测试里钉死联动用例防改名漂移。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final LostItemMapper itemMapper;
    private final ForumPostMapper postMapper;
    private final ForumReplyMapper replyMapper;
    private final ItemClaimMapper claimMapper;
    private final OperationLogMapper opLogMapper;
    private final com.campus.lf.module.item.mapper.CategoryMapper categoryMapper;
    private final com.campus.lf.module.user.mapper.SysUserMapper userMapper;

    /** GET /api/admin/stats/overview 聚合体 */
    public Map<String, Object> overview(LoginUser me) {
        assertStats(me);
        Map<String, Object> out = new HashMap<>();

        // ---- 总量六卡 ----
        out.put("userTotal", userMapper.selectCount(null));
        long itemTotal = itemMapper.selectCount(null);
        out.put("itemTotal", itemTotal);
        long lostCount = itemMapper.selectCount(new LambdaQueryWrapper<com.campus.lf.module.item.entity.LostItem>()
                .eq(com.campus.lf.module.item.entity.LostItem::getType, "LOST"));
        out.put("lostCount", lostCount);
        out.put("foundCount", itemTotal - lostCount);
        out.put("claimTotal", claimMapper.selectCount(null));
        out.put("postTotal", postMapper.selectCount(null));

        // ---- 找回率 ----
        long returned = itemMapper.selectCount(new LambdaQueryWrapper<com.campus.lf.module.item.entity.LostItem>()
                .eq(com.campus.lf.module.item.entity.LostItem::getType, "FOUND")
                .eq(com.campus.lf.module.item.entity.LostItem::getStatus, ItemService.STATUS_OFFLINE)
                .eq(com.campus.lf.module.item.entity.LostItem::getOfflineReason, "已归还"));
        long foundTotal = itemTotal - lostCount;
        out.put("returnedCount", returned);
        out.put("returnRate", foundTotal == 0 ? 0.0 : round2((double) returned / foundTotal));

        // ---- 7 日发布趋势（物品+帖子合并计数；缺日补 0 保折线连续）----
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            LocalDateTime from = d.atStartOfDay(), to = d.plusDays(1).atStartOfDay();
            long items = itemMapper.selectCount(new LambdaQueryWrapper<com.campus.lf.module.item.entity.LostItem>()
                    .ge(com.campus.lf.module.item.entity.LostItem::getCreatedAt, from)
                    .lt(com.campus.lf.module.item.entity.LostItem::getCreatedAt, to));
            long posts = postMapper.selectCount(new LambdaQueryWrapper<com.campus.lf.module.forum.entity.ForumPost>()
                    .ge(com.campus.lf.module.forum.entity.ForumPost::getCreatedAt, from)
                    .lt(com.campus.lf.module.forum.entity.ForumPost::getCreatedAt, to));
            Map<String, Object> point = new HashMap<>();
            point.put("date", d.toString());
            point.put("items", items);
            point.put("posts", posts);
            trend.add(point);
        }
        out.put("trend7d", trend);

        // ---- 分类分布（ITEM 类目按在架件数）----
        List<Map<String, Object>> dist = new ArrayList<>();
        categoryMapper.selectList(new LambdaQueryWrapper<com.campus.lf.module.item.entity.Category>()
                        .eq(com.campus.lf.module.item.entity.Category::getKind, "ITEM")
                        .orderByAsc(com.campus.lf.module.item.entity.Category::getSortOrder))
                .forEach(c -> {
                    long n = itemMapper.selectCount(
                            new LambdaQueryWrapper<com.campus.lf.module.item.entity.LostItem>()
                                    .eq(com.campus.lf.module.item.entity.LostItem::getCategoryId, c.getId()));
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", c.getName());
                    m.put("count", n);
                    dist.add(m);
                });
        out.put("categoryDist", dist);

        // ---- 论坛热度 Top5（回复+点赞合分，走 last() 表达式排序）----
        var hot = postMapper.selectList(
                new LambdaQueryWrapper<com.campus.lf.module.forum.entity.ForumPost>()
                        .eq(com.campus.lf.module.forum.entity.ForumPost::getStatus, 1)
                        .orderByDesc(com.campus.lf.module.forum.entity.ForumPost::getReplyCount)
                        .last("limit 5"));
        out.put("hotPosts", hot.stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("title", p.getTitle());
            m.put("replyCount", p.getReplyCount());
            m.put("likeCount", p.getLikeCount());
            return m;
        }).toList());

        // ---- 治理待办角标 ----
        out.put("pendingItems", itemMapper.selectCount(
                new LambdaQueryWrapper<com.campus.lf.module.item.entity.LostItem>()
                        .eq(com.campus.lf.module.item.entity.LostItem::getStatus, 0)));
        out.put("pendingPosts", postMapper.selectCount(
                new LambdaQueryWrapper<com.campus.lf.module.forum.entity.ForumPost>()
                        .eq(com.campus.lf.module.forum.entity.ForumPost::getStatus, 0)));
        out.put("pendingReplies", replyPendingCount());
        return out;
    }

    private long replyPendingCount() {
        return replyMapper.selectCount(new LambdaQueryWrapper<com.campus.lf.module.forum.entity.ForumReply>()
                .eq(com.campus.lf.module.forum.entity.ForumReply::getStatus, 0));
    }

    /** 操作日志分页（oplog:view）：action/target 过滤 + 时间倒序 */
    public PageResult<OperationLog> pageLogs(LoginUser me, String action, String target,
                                             long pageNum, long pageSize) {
        if (!me.getPerms().contains("oplog:view")) {
            throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
        }
        LambdaQueryWrapper<OperationLog> w = new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getId);
        if (action != null && !action.isBlank()) {
            w.eq(OperationLog::getAction, action.trim());
        }
        if (target != null && !target.isBlank()) {
            w.like(OperationLog::getTarget, target.trim());
        }
        var page = opLogMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize), w);
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                page.getRecords());
    }

    private void assertStats(LoginUser me) {
        if (!me.getPerms().contains("stats:view")) {
            throw new BizException(BizCode.FORBIDDEN, BizCode.FORBIDDEN.getMessage(), 403);
        }
    }

    private double round2(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}

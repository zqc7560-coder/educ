package com.campus.lf.module.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.lf.module.item.entity.LostItem;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.module.notice.NotifyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 失物↔招领匹配推荐（M7 灵魂算法）：新发布的失物（LOST）自动找相似的招领
 * （FOUND）推送 Top-N，反之亦然。
 *
 * <p>打分公式（可解释、答辩可讲，全部权重写死常量便于对齐口径）：
 * <pre>
 *   score = 30 × 分类相同
 *         + 25 × 楼栋相同（楼栋空但校区相同给 12 半分）
 *         + 20 × 时间接近度（|发布日-遗失日| ≤3 天满分，≤7 天半分，之后按天衰减到 0）
 *         + 15 × 标题词重合度（Jaccard）
 *         + 10 × 描述词重合度（Jaccard，权重低因描述噪声大）
 * </pre>
 * 阈值 {@link #MATCH_THRESHOLD}：低于它不推送（宁缺毋滥——推送一堆不相关的
 * 会让用户关掉整个通知通道，比不推送更糟）。
 *
 * <p>分词用极简策略：中文按字符 bigram、ASCII 连续段当一个 token。不引分词器
 * 依赖（毕设环境约束），Jaccard 在这种短标题上效果足够且完全确定可测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    static final int MATCH_THRESHOLD = 30;
    /** 检索窗口：只和近 30 天在架件比（陈旧件匹配意义低且省扫描） */
    static final int CANDIDATE_WINDOW_DAYS = 30;

    private final LostItemMapper itemMapper;
    private final NotificationService notificationService;
    private final com.campus.lf.module.admin.mapper.SysConfigMapper configMapper;

    public record Match(long itemId, String title, int score) {
    }

    /**
     * 对给定物品做一次匹配推送（发布/重上架钩子调用）。
     *
     * @return 推送出去的对端条数（0=没有相似件，测试与日志可见）
     */
    public int matchAndNotify(LostItem item) {
        String opposite = "LOST".equals(item.getType()) ? "FOUND" : "LOST";
        List<LostItem> candidates = itemMapper.selectList(new LambdaQueryWrapper<LostItem>()
                .eq(LostItem::getType, opposite)
                // 1=已发布（ItemService.STATUS_PUBLISHED 字面量化：item→notice→item
                // 构造器循环依赖会炸启动，这里刻意不 import ItemService）
                .eq(LostItem::getStatus, 1)
                .ge(LostItem::getCreatedAt, LocalDateTime.now().minusDays(CANDIDATE_WINDOW_DAYS))
                .ne(LostItem::getUserId, item.getUserId())   // 不给自己推
                .last("limit 500"));                          // 扫描上限（毕设规模保险）

        Set<String> srcTitle = tokenize(item.getTitle());
        Set<String> srcDesc = tokenize(item.getDescription());

        List<Match> hits = new ArrayList<>();
        for (LostItem c : candidates) {
            int score = score(item, c, srcTitle, srcDesc);
            if (score >= MATCH_THRESHOLD) {
                hits.add(new Match(c.getId(), c.getTitle(), score));
            }
        }
        hits.sort(Comparator.comparingInt(Match::score).reversed());
        List<Match> top = hits.stream().limit(topN()).toList();

        for (Match m : top) {
            notificationService.push(item.getUserId(), NotifyType.MATCH, m.itemId(),
                    "可能匹配的" + ("LOST".equals(opposite) ? "招领" : "失物") + "（匹配度 " + m.score() + "）",
                    "《" + m.title() + "》与你发布的《" + item.getTitle() + "》特征相近，去看看是不是你的东西。");
        }
        if (!top.isEmpty()) {
            log.info("物品 {} 匹配推送 {} 条（阈值 {}，候选 {}）",
                    item.getId(), top.size(), MATCH_THRESHOLD, candidates.size());
        }
        return top.size();
    }

    /** 纯打分函数（无副作用，单测直接覆盖公式） */
    public int score(LostItem a, LostItem b, Set<String> aTitleTokens, Set<String> aDescTokens) {
        int s = 0;
        if (a.getCategoryId() != null && a.getCategoryId().equals(b.getCategoryId())) {
            s += 30;
        }
        if (a.getBuildingId() != null && a.getBuildingId().equals(b.getBuildingId())) {
            s += 25;
        } else if (a.getCampusId() != null && a.getCampusId().equals(b.getCampusId())) {
            s += 12;
        }
        s += timeScore(a.getHappenedAt(), b.getHappenedAt());
        s += (int) (15 * jaccard(aTitleTokens, tokenize(b.getTitle())));
        s += (int) (10 * jaccard(aDescTokens, tokenize(b.getDescription())));
        return s;
    }

    /** 时间接近度：双方"遗失/发现日期"差 ≤3 天满分 20，≤7 天 10，≤14 天 5，之后 0 */
    static int timeScore(LocalDateTime x, LocalDateTime y) {
        if (x == null || y == null) {
            return 0;
        }
        long days = Math.abs(java.time.Duration.between(x, y).toDays());
        if (days <= 3) {
            return 20;
        }
        if (days <= 7) {
            return 10;
        }
        if (days <= 14) {
            return 5;
        }
        return 0;
    }

    /** Jaccard 相似度（交集/并集；任一为空 → 0） */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        long inter = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    /**
     * 极简分词：ASCII 连续段成词（小写），中文取字符 bigram（单字也保留，
     * 两字以下的短文本 bigram 会退化成单字集合——可接受，确定性优先）。
     */
    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String t = text.toLowerCase();
        Set<String> tokens = new java.util.HashSet<>();
        StringBuilder ascii = new StringBuilder();
        List<Character> cjk = new ArrayList<>();
        for (char ch : t.toCharArray()) {
            if (ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9') {
                ascii.append(ch);
                flushCjk(cjk, tokens);
            } else if (isCjk(ch)) {
                if (ascii.length() > 0) {
                    tokens.add(ascii.toString());
                    ascii.setLength(0);
                }
                cjk.add(ch);
            } else {
                if (ascii.length() > 0) {
                    tokens.add(ascii.toString());
                    ascii.setLength(0);
                }
                flushCjk(cjk, tokens);
            }
        }
        if (ascii.length() > 0) {
            tokens.add(ascii.toString());
        }
        flushCjk(cjk, tokens);
        tokens.removeIf(String::isEmpty);
        return tokens;
    }

    private static boolean isCjk(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FFF;
    }

    private static void flushCjk(List<Character> cjk, Set<String> out) {
        if (cjk.isEmpty()) {
            return;
        }
        if (cjk.size() == 1) {
            out.add(String.valueOf(cjk.get(0)));
        } else {
            for (int i = 0; i < cjk.size(); i++) {
                out.add(String.valueOf(cjk.get(i)));          // 单字
                if (i + 1 < cjk.size()) {
                    out.add("" + cjk.get(i) + cjk.get(i + 1));  // bigram
                }
            }
        }
        cjk.clear();
    }

    /** Top-N 条数走系统参数 match_recommend_top（缺省 5） */
    private int topN() {
        try {
            var row = configMapper.selectOne(new LambdaQueryWrapper<com.campus.lf.module.admin.entity.SysConfig>()
                    .eq(com.campus.lf.module.admin.entity.SysConfig::getConfigKey, "match_recommend_top"));
            if (row != null) {
                int n = Integer.parseInt(row.getConfigValue());
                return Math.min(Math.max(n, 1), 20);
            }
        } catch (Exception e) {
            log.warn("读取 match_recommend_top 失败，用兜底 5：{}", e.getMessage());
        }
        return 5;
    }
}

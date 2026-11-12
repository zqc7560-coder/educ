package com.campus.lf.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.lf.module.item.entity.LostItem;
import com.campus.lf.module.item.mapper.LostItemMapper;
import com.campus.lf.module.item.service.ItemCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览量回写任务（M9）：lf:pv:{id} 的 Redis 增量批量加回 lost_item.view_count。
 *
 * <p>为什么计数先过 Redis：详情页是全站读 QPS 最高的口，每读一次 UPDATE 主表会把
 * 行锁变成读路径串行点；INCR（读时微秒级）与 5 分钟一次累加回写把读写 QPS 解耦。
 *
 * <p>幂等与宕机取舍（博客 M9 同一套论证）：
 * <ul>
 *   <li>回写是 {@code view_count = view_count + delta} 累加式，delta 来自"取走即归零"
 *       的计数器——同一笔增量只会被取走一次；</li>
 *   <li>GET→DEL 窗口毫秒级新增量、DEL→DB 之间宕机的已取增量，按尽力而为丢弃——
 *       浏览量是统计语义不是账本；</li>
 *   <li>DB 写失败把增量 INCR 回计数器（恢复现场）下轮重试——宁慢不丢。</li>
 * </ul>
 *
 * <p>搭车职责：排空失效补偿队列（lf:evict:retry）。两件事都是"Redis↔DB 事后对账"，
 * 共用一个 5 分钟节拍，不值得各开任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PvSyncJob {

    private final ItemCacheService cache;
    private final LostItemMapper itemMapper;

    /** fixedDelay：上轮结束歇 5 分钟再跑（防 JVM 卡顿时 cron 攒连发）；测试 profile 拨 24h 禁自动 */
    @Scheduled(initialDelayString = "${lf.pv.initial-delay-ms:60000}",
               fixedDelayString = "${lf.pv.sync-interval-ms:300000}")
    public void scheduledSync() {
        try {
            Result r = syncNow();
            if (r.applied() > 0 || r.recovered() > 0) {
                log.info("PV 回写完成: 落库 {} 件 (+{} 浏览), 补偿失效 {} 件",
                        r.applied(), r.totalDelta(), r.recovered());
            }
        } catch (Exception e) {
            // 调度任务抛异常会终止该任务后续执行（Spring 默认），必须整体兜住
            log.error("PV 回写任务异常（下轮重试）", e);
        }
    }

    public record Result(int applied, long totalDelta, long recovered) {
    }

    /** 一轮完整回写（测试直接调用驱动，不与调度线程赛跑） */
    public Result syncNow() {
        long recovered = cache.drainEvictRetry();

        int applied = 0;
        long totalDelta = 0;
        List<String> failures = new ArrayList<>();
        for (String key : cache.scanKeys(ItemCacheService.PV_PREFIX + "*")) {
            long delta = cache.takeCounter(key);
            if (delta <= 0) {
                continue;   // 空/负增量：键刚被并发取走，跳过即清理完成
            }
            try {
                Long itemId = Long.valueOf(key.substring(ItemCacheService.PV_PREFIX.length()));
                // 累加式回写不读旧值再写（读-改-写并发互相吞）；物品已删命中 0 行——
                // 增量随键消失，正确：没人再看得到的计数不需要落账
                itemMapper.update(null, new LambdaUpdateWrapper<LostItem>()
                        .eq(LostItem::getId, itemId)
                        .setSql("view_count = view_count + " + delta));
                applied++;
                totalDelta += delta;
            } catch (NumberFormatException e) {
                log.warn("异常 PV 键跳过并清理 key={}", key);
            } catch (Exception e) {
                cache.restoreCounter(key, delta);   // 恢复现场下轮重试（宁慢不丢）
                failures.add(key);
            }
        }
        if (!failures.isEmpty()) {
            log.warn("PV 回写 {} 个键失败已回补计数器，下轮重试", failures.size());
        }
        return new Result(applied, totalDelta, recovered);
    }
}

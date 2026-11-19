package com.campus.lf.module.item.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 浏览量去重门（用户要求：一个用户多次点击只能算一次浏览）。
 *
 * <p>访客身份=登录用户 {@code u:{id}}，游客 {@code ip:{remoteAddr}}（X-Real-IP 优先，
 * nginx 已注入）。首次见某目标写入 Redis SETNX 带 TTL（24h）——写成功=该访客
 * 今天第一次看，计数 +1；已存在=复访，静默不计数。Redis 抖动按"计数一次"放行
 * （fail-open：浏览量是统计语义不是账本语义，与 PV 计数同口径）。
 *
 * <p>物品/帖子共用本门（键加类型前缀隔离）。TTL 语义=24 小时滑窗去重，
 * 毕设口径够用；若要"永久只算一次"改 TTL 为 -1（键常驻，量级可控）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewOnceGate {

    private static final String PREFIX = "lf:pvseen:";
    private static final Duration WINDOW = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    /** 该访客是否应计数（true=本窗口首次访问该目标） */
    public boolean shouldCount(String kind, long targetId, String viewerKey) {
        if (viewerKey == null || viewerKey.isBlank()) {
            return true;   // 拿不到访客身份（如测试直连）：退回"每次计数"旧语义
        }
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(PREFIX + kind + ":" + targetId + ":" + viewerKey,
                    "1", WINDOW);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("浏览去重门降级放行 {}:{} viewer={}: {}", kind, targetId, viewerKey, e.getMessage());
            return true;
        }
    }
}

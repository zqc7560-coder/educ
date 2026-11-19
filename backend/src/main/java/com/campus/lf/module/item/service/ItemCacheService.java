package com.campus.lf.module.item.service;

import com.campus.lf.module.item.vo.ItemVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 物品热点缓存（M9）——Cache-Aside + 写后双删 + PV Redis 计数（开发方案 §5 性能设计的落地件）。
 *
 * <p>键空间（统一 lf: 前缀，与博客 blog:/default profile 隔离开——两系统可同 Redis 共存）：
 * <ul>
 *   <li>{@code lf:item:{id}} String(JSON) 30min —— 详情缓存。<b>只缓存"已发布件的
 *       公开视图"</b>（me=null 装配）：待审/下架件的可见性与读者身份相关，混进缓存
 *       就是权限穿透；公开视图人人相同，命中路径可以安全跳过可见性矩阵。</li>
 *   <li>{@code lf:list:{md5(规范化参数)}} String(JSON) 5min —— 列表缓存。只缓存
 *       公开检索分支（非 mine）；短 TTL 是失效路径漏删时的自愈兜底。</li>
 *   <li>{@code lf:listkeys} Set —— 列表键登记处：失效按清单逐个 DEL，不跑 KEYS
 *       模式扫（生产大库上阻塞）。</li>
 *   <li>{@code lf:evict:retry} Set —— DEL 失败的补偿队列，PvSyncJob 每轮顺带排空。</li>
 *   <li>{@code lf:pv:{id}} 计数键 —— 浏览量 INCR 过 Redis，定时批量回写 view_count，
 *       读路径不再 UPDATE 主表（行锁热点消除）。</li>
 * </ul>
 *
 * <p>两条纪律（博客 M9 同套被压测验证过的方案）：
 * ① 写后删（DEL）不写后更新——并发读写下"更新"有旧覆盖新的竞态，"删"没有；
 * ② 缓存是加速器不是正确性依赖——所有 Redis 操作 try-catch，读失败按未命中回源，
 * 删失败进补偿队列，<b>Redis 整体故障时全站自动退化为直查 DB，无 500</b>（台账验收项）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemCacheService {

    private static final Duration DETAIL_TTL = Duration.ofMinutes(30);
    private static final Duration LIST_TTL = Duration.ofMinutes(5);

    public static final String DETAIL_PREFIX = "lf:item:";
    public static final String LIST_PREFIX = "lf:list:";
    public static final String LIST_INDEX = "lf:listkeys";
    public static final String EVICT_RETRY = "lf:evict:retry";
    public static final String PV_PREFIX = "lf:pv:";

    private final StringRedisTemplate redis;
    /** 应用级 ObjectMapper：进缓存的 JSON 与给前端的 JSON 同套序列化配置，防形状漂移 */
    private final ObjectMapper objectMapper;

    /** 缓存载荷信封（PageResult 无空参构造不直接序列化；record 走规范构造器） */
    public record CachePage(long total, long pages, long pageNum, long pageSize, List<ItemVO> list) {
    }

    // ====================== 详情 ======================

    /** 读详情缓存；异常与未命中折叠为 null（调用方统一回源，不需要区分） */
    public ItemVO getDetail(Long id) {
        try {
            String json = redis.opsForValue().get(DETAIL_PREFIX + id);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, ItemVO.class);
        } catch (Exception e) {
            log.warn("详情缓存读取失败（按未命中处理）id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /** 回填详情缓存（仅发布态公开视图调用，权限论证见类注释） */
    public void putDetail(Long id, ItemVO vo) {
        try {
            redis.opsForValue().set(DETAIL_PREFIX + id,
                    objectMapper.writeValueAsString(vo), DETAIL_TTL);
        } catch (Exception e) {
            log.warn("详情缓存写入失败 id={}: {}", id, e.getMessage());
        }
    }

    // ====================== 列表 ======================

    public String listKey(String canonical) {
        return LIST_PREFIX + md5Hex(canonical);
    }

    public CachePage getList(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, CachePage.class);
        } catch (Exception e) {
            log.warn("列表缓存读取失败（按未命中处理）key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public void putList(String key, CachePage page) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(page), LIST_TTL);
            redis.opsForSet().add(LIST_INDEX, key);
        } catch (Exception e) {
            log.warn("列表缓存写入失败 key={}: {}", key, e.getMessage());
        }
    }

    /** 规范化参数串：同查询不同书写（空白/缺省）必须同键，否则旧值各持一份 */
    public static String canonical(String type, Long categoryId, String keyword, Long campusId,
                                   Long buildingId, String from, String to, String sort,
                                   long pageNum, long pageSize) {
        return String.join("|",
                nz(type == null ? "" : type.trim().toUpperCase()),
                nz(categoryId), nz(keyword == null ? "" : keyword.trim()),
                nz(campusId), nz(buildingId), nz(from), nz(to),
                nz(sort == null ? "" : sort.trim()), String.valueOf(pageNum), String.valueOf(pageSize));
    }

    // ====================== 失效 ======================

    /** 失效一件物品的全部可见缓存：详情键 + 所有登记列表键 */
    public void evict(Long itemId) {
        try {
            redis.delete(DETAIL_PREFIX + itemId);
            Set<String> listKeys = redis.opsForSet().members(LIST_INDEX);
            if (listKeys != null) {
                for (String k : listKeys) {
                    redis.delete(k);
                }
                if (!listKeys.isEmpty()) {
                    redis.delete(LIST_INDEX);
                }
            }
        } catch (Exception e) {
            log.warn("缓存失效失败，转入补偿队列 id={}: {}", itemId, e.getMessage());
            try {
                redis.opsForSet().add(EVICT_RETRY, String.valueOf(itemId));
            } catch (Exception ignore) {
                // Redis 整体不可用时无处可存，靠短 TTL 自愈
            }
        }
    }

    /**
     * 事务安全失效（写路径统一入口）双删结构：
     * ① 立即 DEL（事务内读与"改完即查"拿到回源新值）；
     * ② afterCommit 再 DEL（堵住"提交前并发请求回源把旧值写回缓存"的经典竞态窗口）。
     */
    public void evictTx(Long itemId) {
        evict(itemId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(itemId);
                }
            });
        }
    }

    /** 排空补偿队列（PvSyncJob 搭车执行） */
    public long drainEvictRetry() {
        long drained = 0;
        try {
            Set<String> pending = redis.opsForSet().members(EVICT_RETRY);
            if (pending == null || pending.isEmpty()) {
                return 0;
            }
            for (String idStr : pending) {
                try {
                    evict(Long.parseLong(idStr));
                    redis.opsForSet().remove(EVICT_RETRY, idStr);
                    drained++;
                } catch (Exception e) {
                    log.warn("补偿失效失败 id={}: {}", idStr, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("补偿队列读取失败（下轮再试）: {}", e.getMessage());
        }
        return drained;
    }

    // ====================== PV 计数 ======================

    /** 浏览计一次：两个微秒级 INCR 换掉读路径 UPDATE 主表的行锁热点 */
    public void countPv(Long itemId) {
        try {
            redis.opsForValue().increment(PV_PREFIX + itemId);
        } catch (Exception e) {
            log.warn("PV 计数失败（丢一笔无妨）id={}: {}", itemId, e.getMessage());
        }
    }

    /** 原子取走计数并归零（get→delete 两步：单实例毕设部署下窗口毫秒级，
     *  期间新增量按"尽力而为"丢弃——浏览量是统计语义不是账本语义） */
    public long takeCounter(String key) {
        try {
            String s = redis.opsForValue().get(key);
            if (s == null) {
                return 0;
            }
            redis.delete(key);
            return Long.parseLong(s);
        } catch (Exception e) {
            log.warn("计数器取走失败 key={}: {}", key, e.getMessage());
            return 0;
        }
    }

    /** 回写失败时恢复增量（宁慢不丢） */
    public void restoreCounter(String key, long delta) {
        try {
            redis.opsForValue().increment(key, delta);
        } catch (Exception e) {
            log.warn("计数器恢复失败 key={} delta={}: {}", key, delta, e.getMessage());
        }
    }

    /** 批量读未落盘 PV 缓冲（列表展示叠用；一次 MGET，Redis 抖动返回空 map=不叠） */
    public java.util.Map<Long, Long> pvDeltas(java.util.List<Long> ids) {
        java.util.Map<Long, Long> out = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        try {
            List<String> vals = redis.opsForValue().multiGet(
                    ids.stream().map(i -> PV_PREFIX + i).toList());
            if (vals != null) {
                for (int i = 0; i < ids.size() && i < vals.size(); i++) {
                    String v = vals.get(i);
                    if (v != null && !v.isBlank()) {
                        out.put(ids.get(i), Long.parseLong(v));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("PV 缓冲批量读失败（列表按 DB 值展示）: {}", e.getMessage());
        }
        return out;
    }

    public long peekCounter(String key) {
        try {
            String s = redis.opsForValue().get(key);
            return s == null ? 0 : Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    public Set<String> scanKeys(String pattern) {
        try {
            Set<String> keys = redis.keys(pattern);
            return keys == null ? Set.of() : keys;
        } catch (Exception e) {
            log.warn("键扫描失败 pattern={}: {}", pattern, e.getMessage());
            return Set.of();
        }
    }

    /** 运维口：全量清空（管理端"缓存管理"按钮；参数改动后的兜底通道） */
    public long evictAll() {
        long n = 0;
        try {
            for (String k : redis.keys(DETAIL_PREFIX + "*")) {
                redis.delete(k);
                n++;
            }
            for (String k : redis.keys(LIST_PREFIX + "*")) {
                redis.delete(k);
                n++;
            }
            redis.delete(LIST_INDEX);
            redis.delete(EVICT_RETRY);
        } catch (Exception e) {
            log.warn("全量清缓存失败: {}", e.getMessage());
        }
        return n;
    }

    private static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String md5Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

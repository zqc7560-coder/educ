package com.campus.lf.support;

import com.campus.lf.module.item.service.ItemCacheService;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 测试缓存卫生守卫（M9）——junit-platform.properties 开自动探测后对所有
 * @SpringBootTest 无感生效：每个用例执行前清空共享内存 Redis 的 lf: 缓存键族。
 *
 * <p>为什么必须存在：M9 后详情/列表缓存放进随 Spring 上下文存活的内存 Redis，
 * 而类级 @Transactional 只回滚 H2——Redis 不跟着回滚。A 用例回填的列表键，
 * B 用例（乃至另一个测试类，上下文是共享的）回滚 DB 后缓存仍在，"改库→读接口"
 * 就会读到旧值——这不是缓存 bug，恰恰是"写没失效就是脏"的正确证明，但对测试
 * 是纯污染。守卫让每个用例从缓存空态起跑。
 *
 * <p>非 Spring 语境的纯单元测试取不到 ApplicationContext 时静默跳过。
 */
public class TestCacheGuardExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        ApplicationContext ac;
        try {
            ac = SpringExtension.getApplicationContext(context);
        } catch (Exception e) {
            return;   // 纯单元测试上下文，无 Bean 可清
        }
        ac.getBeanProvider(ItemCacheService.class).ifAvailable(ItemCacheService::evictAll);
        ac.getBeanProvider(StringRedisTemplate.class).ifAvailable(t -> {
            try {
                // PV 计数器/补偿队列同清：A 用例点的浏览量渗进 B 的回写断言
                // 就是"数字自己动"的经典假阳性源
                t.delete(ItemCacheService.EVICT_RETRY);
                for (String pattern : new String[]{ItemCacheService.PV_PREFIX + "*",
                        // 论坛防刷锁键：内存桩吞 TTL，lf:gate:reply:* 会永驻共享上下文——
                        // 同一自增 uid 跨测试类撞上限就是纯污染（生产靠 10s TTL 自然清）
                        "lf:gate:reply:*"}) {
                    for (String k : t.keys(pattern)) {
                        // 逐个 delete(String)：桩只 mock 了单键形态，集合形态是空操作
                        t.delete(k);
                    }
                }
            } catch (Exception ignore) {
                // 内存版不会抛；真 Redis 集成语境清失败也只是退回脏缓存路径，不阻断测试
            }
        });
    }
}

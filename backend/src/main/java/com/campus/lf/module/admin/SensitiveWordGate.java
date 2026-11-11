package com.campus.lf.module.admin;

import com.campus.lf.common.BizCode;
import com.campus.lf.common.BizException;
import com.campus.lf.module.admin.entity.SensitiveWord;
import com.campus.lf.module.admin.mapper.SensitiveWordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 敏感词闸门（治理基础设施，M3 起被物品/论坛/公告三域写口共用，M8 配管理界面）。
 *
 * <p>策略：词库量级小（千级），全表驻内存 Set 每次判定全词 contains 包含匹配；
 * 词库任何写操作（M8）后调用 refresh() 重灌——读写不对称，读侧几乎零成本。
 *
 * <p>命中即拒（400 + 命中词不回显——回显等于教用户怎么绕词表）而不是替换成 *：
 * 失物描述是"凭证性文本"，静默改写会破坏内容与照片的一致性，拒收让用户改原文
 * 才是诚实处置。命中明细进应用日志供治理复盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordGate {

    private final SensitiveWordMapper wordMapper;

    /** volatile 单引用：refresh 换新 Set，读侧无锁 */
    private volatile Set<String> words = Set.of();

    /** 校验文本：命中任一敏感词 → 400；空词库/空文本直通 */
    public void assertClean(String text, String fieldLabel) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (words.isEmpty()) {
            load(); // 首次调用惰性灌库（Runner 之外的兜底：测试上下文不跑 Runner）
        }
        String lower = text.toLowerCase();
        for (String w : words) {
            if (lower.contains(w)) {
                // 日志留命中词，响应不回显（见类注释）
                log.info("敏感词拦截 field={} hit={}", fieldLabel, w);
                throw new BizException(BizCode.PARAM_ERROR, fieldLabel + "包含违规内容，请修改后重试");
            }
        }
    }

    /** 词库写后重灌（M8 管理接口调用） */
    public synchronized void refresh() {
        List<SensitiveWord> all = wordMapper.selectList(null);
        words = all.stream()
                .map(w -> w.getWord().toLowerCase())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void load() {
        refresh();
    }
}

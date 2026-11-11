package com.campus.lf.module.admin.service;

import com.campus.lf.module.admin.entity.OperationLog;
import com.campus.lf.module.admin.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作审计流水（M8）：管理员治理动作的唯一留痕口。
 *
 * <p>与 claim_log（业务流程审计）分工：operation_log 只记"治理者干了什么"
 * （审核/下架/封禁/参数改动/词库增删），业务用户的正常操作不进这里——
 * 表语义纯净，管理员回溯与答辩演示都只看一张表。
 *
 * <p>写失败吞异常只记应用日志：审计是旁路，绝不能因为流水表抖动把审核主流程 500。
 * （真生产环境反过来——审计必须可靠投递；毕设单机口径权衡，注释说清楚。）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogMapper logMapper;

    /** action 词表（常量收口，防裸字符串漂移）：动作-对象两段式 */
    public static final String ITEM_AUDIT_PASS = "ITEM_AUDIT_PASS";
    public static final String ITEM_AUDIT_REJECT = "ITEM_AUDIT_REJECT";
    public static final String ITEM_OFFLINE = "ITEM_OFFLINE";
    public static final String POST_AUDIT_PASS = "POST_AUDIT_PASS";
    public static final String POST_AUDIT_REJECT = "POST_AUDIT_REJECT";
    public static final String POST_OFFLINE = "POST_OFFLINE";
    public static final String REPLY_BLOCK = "REPLY_BLOCK";
    public static final String USER_MUTE = "USER_MUTE";
    public static final String USER_BAN = "USER_BAN";
    public static final String USER_UNBAN = "USER_UNBAN";
    public static final String USER_ROLES = "USER_ROLES";
    public static final String WORD_ADD = "WORD_ADD";
    public static final String WORD_DELETE = "WORD_DELETE";
    public static final String CONFIG_UPDATE = "CONFIG_UPDATE";
    public static final String ANNOUNCE_PUBLISH = "ANNOUNCE_PUBLISH";
    public static final String ANNOUNCE_OFFLINE = "ANNOUNCE_OFFLINE";

    public void record(Long operatorId, String action, String target, String detail) {
        try {
            OperationLog o = new OperationLog();
            o.setUserId(operatorId);
            o.setAction(action);
            o.setTarget(target);
            o.setDetail(detail == null ? null : (detail.length() > 500 ? detail.substring(0, 497) + "…" : detail));
            o.setCreatedAt(LocalDateTime.now());
            logMapper.insert(o);
        } catch (Exception e) {
            log.warn("审计流水写入失败 action={} target={}：{}", action, target, e.getMessage());
        }
    }
}

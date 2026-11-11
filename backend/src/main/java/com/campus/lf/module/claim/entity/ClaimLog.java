package com.campus.lf.module.claim.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 认领流转留痕 —— schema.sql §11。append-only：无 updated/deleted，日志只增不改不删是审计语义的地基。
 */
@Data
@TableName("claim_log")
public class ClaimLog {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 item_claim.id */
    private Long claimId;

    /** 操作人（系统自动关闭为 NULL） */
    private Long actorId;

    /** APPLY/VERIFY_PASS/VERIFY_REJECT/CONFIRM_RETURN/ARBITRATE_APPLY/ARBITRATE_JUDGE/CLOSED_BY_RETURN/TIMEOUT_REMIND */
    private String action;

    /** 理由/备注 */
    private String detail;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;
}

package com.campus.lf.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志 —— schema.sql §21。管理端写操作全留痕；append-only 同 ClaimLog 纪律。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 */
    private Long userId;

    /** 动作码（ITEM_OFFLINE/ANN_PUBLISH/USER_BAN...） */
    private String action;

    /** 目标对象标识（item:123） */
    private String target;

    /** 明细 */
    private String detail;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;
}

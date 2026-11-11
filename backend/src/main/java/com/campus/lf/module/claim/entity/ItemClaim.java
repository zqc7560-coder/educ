package com.campus.lf.module.claim.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * 认领申请单 —— schema.sql §10。状态机：0待核验→1约定交接→2已完成；0/1→3已拒绝；→4仲裁中→2或3；他人先归还→5已关闭。uk(item_id,claimant_id)=同人重复申请拦截（3003）。
 */
@Data
@TableName("item_claim")
public class ItemClaim {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 lost_item.id */
    private Long itemId;

    /** 认领申请人 */
    private Long claimantId;

    /** 核验答案（FOUND 件物主问题的回答） */
    private String answer;

    /** 补充自述凭证 */
    private String evidenceNote;

    /** 状态机取值见类注释 */
    private Integer status;

    /** 拒绝/仲裁理由（必填执法在 Service） */
    private String rejectReason;

    /** 仲裁管理员 id */
    private Long arbitratedBy;

    /** 核验/裁决落定时间 */
    private LocalDateTime handledAt;

    /** 乐观锁：确认归还先到先得防重复（3004） */
    @Version
    private Integer version;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

    /** 逻辑删除位：@TableLogic，查询由 MP 自动拼 deleted=0 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /** 更新时间：MySQL ON UPDATE CURRENT_TIMESTAMP 维护 */
    private LocalDateTime updatedAt;
}

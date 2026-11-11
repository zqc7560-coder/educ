package com.campus.lf.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色实体 —— schema.sql §2。STUDENT/MANAGER/ADMIN 三角色。
 */
@Data
@TableName("sys_role")
public class SysRole {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色码（ROLE_ 前缀，与 JWT claims 对齐） */
    private String roleCode;

    /** 角色显示名 */
    private String roleName;

    /** 职责描述 */
    private String description;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

    /** 逻辑删除位：@TableLogic，查询由 MP 自动拼 deleted=0 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /** 更新时间：MySQL ON UPDATE CURRENT_TIMESTAMP 维护 */
    private LocalDateTime updatedAt;
}

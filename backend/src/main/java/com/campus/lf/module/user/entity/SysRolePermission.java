package com.campus.lf.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限关联 —— schema.sql §5。鉴权链路：sys_role → 本表 → sys_permission 聚合权限点集合。
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 sys_role.id */
    private Long roleId;

    /** 关联 sys_permission.id */
    private Long permId;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

}

package com.campus.lf.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-角色关联 —— schema.sql §3。多对多：一个人可同是学生+失物中心干事。不设 deleted：授权调整是“重算覆盖”而非“回收”（博客同款语义）。
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 sys_user.id */
    private Long userId;

    /** 关联 sys_role.id */
    private Long roleId;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

}

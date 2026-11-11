package com.campus.lf.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限点实体 —— schema.sql §4。粒度“资源:动作”，13 个权限点由种子写入；登录时经 role_permission 聚合写进 JWT，避免每请求联表。
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限码（如 item:audit，uk_perm_code） */
    private String permCode;

    /** 权限显示名 */
    private String permName;

    /** 所属模块（item/claim/forum/announce/user/system/stats） */
    private String module;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

    /** 逻辑删除位：@TableLogic，查询由 MP 自动拼 deleted=0 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /** 更新时间：MySQL ON UPDATE CURRENT_TIMESTAMP 维护 */
    private LocalDateTime updatedAt;
}

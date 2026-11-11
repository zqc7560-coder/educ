package com.campus.lf.module.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分类/板块合一字典 —— schema.sql §6。kind 隔离两副面孔：ITEM=物品分类，BOARD=论坛板块，互不串查询（uk 含 kind）。
 */
@Data
@TableName("category")
public class Category {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 名称（同 kind 内唯一） */
    private String name;

    /** ITEM=物品分类 BOARD=论坛板块 */
    private String kind;

    /** 图标 URL */
    private String icon;

    /** 列表排序（小在前） */
    private Integer sortOrder;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

    /** 逻辑删除位：@TableLogic，查询由 MP 自动拼 deleted=0 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /** 更新时间：MySQL ON UPDATE CURRENT_TIMESTAMP 维护 */
    private LocalDateTime updatedAt;
}

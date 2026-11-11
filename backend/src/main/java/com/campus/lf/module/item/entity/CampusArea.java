package com.campus.lf.module.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 校区/楼栋两级自连接字典 —— schema.sql §7。level=1 校区、level=2 楼栋（parent 指校区）；三级地点=校区-楼栋-location_text 自由文本。
 */
@Data
@TableName("campus_area")
public class CampusArea {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 名称（同父下唯一，uk_area_name_parent） */
    private String name;

    /** 1=校区 2=楼栋 */
    private Integer level;

    /** 上级 id（楼栋→校区；校区为 NULL） */
    private Long parentId;

    /** 列表排序 */
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

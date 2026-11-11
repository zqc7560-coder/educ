package com.campus.lf.module.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏 —— schema.sql §22。target_type 统一物品/帖子；取消即物理删（幂等事实源=uk_fav_user_target）。
 */
@Data
@TableName("user_favorite")
public class UserFavorite {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收藏人 */
    private Long userId;

    /** ITEM/POST */
    private String targetType;

    /** 目标 id */
    private Long targetId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

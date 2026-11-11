package com.campus.lf.module.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子点赞 —— schema.sql §15。幂等事实源=uk_like_post_user：再点=物理 DELETE，计数同事务结算；无 deleted（“关系不存在”就是物理删）。
 */
@Data
@TableName("forum_like")
public class ForumLike {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 forum_post.id */
    private Long postId;

    /** 点赞人 */
    private Long userId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}

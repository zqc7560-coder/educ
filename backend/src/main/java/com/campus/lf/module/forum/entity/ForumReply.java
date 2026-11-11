package com.campus.lf.module.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 论坛回复 —— schema.sql §14。两级封顶：parent_id 只允许指向顶级回复且同帖（越级/跨帖/幽灵父三查合一 5002）；reply_to_uid 实现自动@。
 */
@Data
@TableName("forum_reply")
public class ForumReply {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 forum_post.id */
    private Long postId;

    /** 父回复（必须顶级+同帖） */
    private Long parentId;

    /** 回复作者 */
    private Long userId;

    /** 被@用户（缺省=父回复作者） */
    private Long replyToUid;

    /** 内容（1000 字符钳制） */
    private String content;

    /** 0待审核 1已发布 2已屏蔽 */
    private Integer status;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

    /** 逻辑删除位：@TableLogic，查询由 MP 自动拼 deleted=0 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /** 更新时间：MySQL ON UPDATE CURRENT_TIMESTAMP 维护 */
    private LocalDateTime updatedAt;
}

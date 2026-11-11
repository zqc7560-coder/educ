package com.campus.lf.module.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * 论坛帖子 —— schema.sql §12。状态机同物品（0待审→1已发布⇄2下架）；content 纯文本（jsoup 清洗后入库）；计数列随行为结算。
 */
@Data
@TableName("forum_post")
public class ForumPost {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 板块（category kind=BOARD） */
    private Long boardId;

    /** 作者 */
    private Long userId;

    /** 标题 */
    private String title;

    /** 正文纯文本（XSS 双防线第一道在服务端） */
    private String content;

    /** 0待审核 1已发布 2已下架 */
    private Integer status;

    /** 下架理由 */
    private String offlineReason;

    /** 浏览数冗余 */
    private Integer viewCount;

    /** 回复数冗余（两级都计） */
    private Integer replyCount;

    /** 点赞数冗余（uk 增删同事务结算） */
    private Integer likeCount;

    /** 精华/置顶（管理员治理） */
    private Integer topFlag;

    /** 首次发布时间（排序基准） */
    private LocalDateTime publishedAt;

    /** 乐观锁 */
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

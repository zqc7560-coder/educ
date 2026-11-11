package com.campus.lf.module.announce.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * 管理员公告 —— schema.sql §16。状态机 0草稿→1已发布⇄2已下架；expire_at 到点定时任务自动下架（M6）。
 */
@Data
@TableName("announcement")
public class Announcement {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标题 */
    private String title;

    /** 正文（纯文本，前端保留换行渲染） */
    private String content;

    /** 0草稿 1已发布 2已下架 */
    private Integer status;

    /** 置顶排前 */
    private Integer topFlag;

    /** 生效时间（NULL=发布即生效） */
    private LocalDateTime effectAt;

    /** 过期时间（NULL=永久） */
    private LocalDateTime expireAt;

    /** 浏览数 */
    private Integer viewCount;

    /** 发布管理员 */
    private Long authorId;

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

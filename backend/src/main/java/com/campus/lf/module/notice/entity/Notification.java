package com.campus.lf.module.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内消息 —— schema.sql §18。biz_type+biz_id 回链业务对象；已读只翻 read_flag（消息不可删，历史留底）。
 */
@Data
@TableName("notification")
public class Notification {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收件人 */
    private Long userId;

    /** ITEM_AUDIT/CLAIM_NEW/CLAIM_RESULT/RETURN_CONFIRM/POST_AUDIT/POST_INTERACT/MATCH_RECOMMEND/ANNOUNCEMENT/SYSTEM */
    private String bizType;

    /** 关联业务对象 id */
    private Long bizId;

    /** 消息标题 */
    private String title;

    /** 消息正文 */
    private String content;

    /** 0未读 1已读 */
    private Integer readFlag;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

    /** 逻辑删除位：@TableLogic，查询由 MP 自动拼 deleted=0 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}

package com.campus.lf.module.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子举报 —— schema.sql §15b。治理工单语义：举报只登记+通知管理员，帖子状态不因
 * 举报改变（区别于旧"举报即转待审"）。幂等事实源=uk_report_post_user（一人一帖一次）。
 */
@Data
@TableName("post_report")
public class PostReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 forum_post.id */
    private Long postId;

    /** 举报人 */
    private Long userId;

    /** 举报理由 */
    private String reason;

    /** 0待处理 1已处置 */
    private Integer status;

    private LocalDateTime createdAt;

    /** 处置时间 */
    private LocalDateTime handledAt;

    @TableLogic
    private Integer deleted;
}

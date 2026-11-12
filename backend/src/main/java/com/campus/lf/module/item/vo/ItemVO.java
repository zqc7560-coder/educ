package com.campus.lf.module.item.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物品出参（列表/详情共用一种形状，字段按场景填充）。
 *
 * <p>可见性矩阵的落点：contact 仅登录用户填充（游客 null）；作者与管理端
 * 额外看到 offlineReason。VO 白名单化（不直接出实体），deleted/version 等
 * 内部列不出现在响应。
 */
@Data
@Builder
// M9 缓存回环：详情/列表 VO 要进 Redis 再读回，@Builder 会吃掉无参构造导致
// Jackson "no Creators"（博客 M9 实测坑），显式补无参+全参
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class ItemVO {

    private Long id;

    /** LOST/FOUND */
    private String type;

    private String title;
    private String description;

    /** 分类 id + 显示名（联查装配，前端展示免二跳） */
    private Long categoryId;
    private String categoryName;

    /** 地点三件套：校区名-楼栋名-自由文本（装配期联查字典） */
    private String campusName;
    private String buildingName;
    private String locationText;

    private LocalDateTime happenedAt;

    /** 联系方式：仅登录可见（未登录 null） */
    private String contact;

    /** 核验问题：公开可见（申请人必须看到才能作答；答案仅发布者可见——在认领单侧） */
    private String verifyQuestion;

    /** 状态 0待审核/1已发布/2已下架 */
    private Integer status;

    /** 下架理由：仅作者与治理端可见 */
    private String offlineReason;

    /** 发布者：id+昵称（作者主页跳转锚点） */
    private Long userId;
    private String userNickname;

    /** 图片 URL 列表（详情装配；列表页省带宽不装） */
    private List<String> images;

    private Integer viewCount;
    private Integer claimCount;
    /** 置顶/加急标记 */
    private Integer topFlag;

    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;

    /** 乐观锁版本：详情回传供编辑窗口携带（列表不带——列表不编辑） */
    private Integer version;
}

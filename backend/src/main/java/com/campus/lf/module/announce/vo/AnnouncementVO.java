package com.campus.lf.module.announce.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告出参（读者/管理共用；管理列表 images=null，详情带图）。
 */
@Data
@Builder
public class AnnouncementVO {

    private Long id;
    private String title;
    private String content;
    /** 0 草稿 / 1 已发布 / 2 已下架（读者侧永远只见 1 且窗口内） */
    private Integer status;
    private Integer topFlag;
    private LocalDateTime effectiveAt;
    private LocalDateTime expireAt;
    private Integer viewCount;
    private Long authorId;
    private LocalDateTime createdAt;
    private Integer version;
    private List<String> images;
}

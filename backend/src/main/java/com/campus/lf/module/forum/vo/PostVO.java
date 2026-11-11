package com.campus.lf.module.forum.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子出参（列表带摘要，详情带全文与图片）。
 */
@Data
@Builder
public class PostVO {

    private Long id;
    private Long boardId;
    private String boardName;
    private Long userId;
    private String nickname;
    private String avatar;
    private String title;
    /** 列表页=前 120 字摘要；详情=全文 */
    private String content;
    /** 仅详情填充 */
    private List<String> images;
    private Integer status;
    private String offlineReason;
    private Integer viewCount;
    private Integer replyCount;
    private Integer likeCount;
    private Integer topFlag;
    /** 当前登录者是否已赞（游客恒 false） */
    private Boolean liked;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private Integer version;
}

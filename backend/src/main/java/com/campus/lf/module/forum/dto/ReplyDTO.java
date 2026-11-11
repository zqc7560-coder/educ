package com.campus.lf.module.forum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 回复请求体（POST /api/forum/posts/{id}/replies）。
 *
 * <p>两级封顶规则的入口裁决：parentId 只允许指向"一级回复"（其自身 parent_id 为
 * null）——给二级回复再回复时，前端应把 parentId 归一为那条一级回复，@对象
 * 用 replyToUserId 表达（service 校验+自动回填）。
 */
@Data
public class ReplyDTO {

    /** 父回复 id（可选；仅允许一级回复的 id，二级挂楼结构） */
    private Long parentId;

    /** @ 谁（可选；service 校验必须是本帖内真实用户，回复二级时自动推导） */
    private Long replyToUserId;

    @NotBlank(message = "回复内容不能为空")
    @Size(max = 1000, message = "回复最长1000字")
    private String content;
}

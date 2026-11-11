package com.campus.lf.module.forum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发帖/编辑请求体（POST /api/forum/posts、PUT /api/forum/posts/{id}）。
 *
 * <p>图片 0–9 张：imageUrls 传"已上传的 /uploads 相对路径"（先 upload 后发帖，
 * 与 M3 物品同纪律——外链在 service 层 400 拒绝）。编辑时整包替换图片集。
 */
@Data
public class PostSaveDTO {

    @NotNull(message = "必须选择板块")
    private Long boardId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长100字")
    private String title;

    @NotBlank(message = "正文不能为空")
    @Size(max = 5000, message = "正文最长5000字")
    private String content;

    @Size(max = 9, message = "最多 9 张图")
    private List<String> imageUrls;

    /** 期望目标状态：null/0=待审（默认），1=请求直发（forum_audit=0 时兑现，=1 时静默降级） */
    private Integer status;

    /** 编辑时的乐观锁版本（GET 详情回传，冲突 2004） */
    private Integer version;
}

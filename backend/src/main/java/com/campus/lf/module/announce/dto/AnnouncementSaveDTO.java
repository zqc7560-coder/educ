package com.campus.lf.module.announce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告创建/编辑请求体（POST/PUT /api/admin/announcements[/{id}]）。
 *
 * <p>状态不在这里传——公告的发布/下架走独立 PATCH 动作口（状态机迁移
 * 与内容编辑分离，防止"编辑即发布"绕过草稿审阅）。
 */
@Data
public class AnnouncementSaveDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长100字")
    private String title;

    @NotBlank(message = "内容不能为空")
    @Size(max = 5000, message = "内容最长5000字")
    private String content;

    /** 生效窗口（可空=长期）：effectiveAt 未到不展示；expireAt 已过自动下架 */
    private LocalDateTime effectiveAt;
    private LocalDateTime expireAt;

    /** 0–9 图（/uploads 相对路径，与论坛同纪律） */
    @Size(max = 9, message = "最多 9 张图")
    private List<String> imageUrls;

    /** 编辑时的乐观锁版本（GET 回传值原样带回；缺省退回服务端现值=不比对） */
    private Integer version;
}

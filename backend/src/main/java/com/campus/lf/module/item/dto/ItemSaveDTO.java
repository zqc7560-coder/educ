package com.campus.lf.module.item.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物品发布/更新请求体（POST/PUT /api/items）。
 *
 * <p>白名单纪律：type/status/author/counts/version 之外的治理字段不可从本 DTO
 * 注入；status 仅允许 0（草稿预留）/1（直接发布，开关关闭时）两值入新建口，
 * "下线/重上架"走独立 PATCH（每次状态动作一个明确入口，审计语义清晰）。
 */
@Data
public class ItemSaveDTO {

    /** LOST=寻物启事 FOUND=招领启事（大小写宽容，Service 归一化大写） */
    @NotBlank(message = "type 不能为空")
    @Pattern(regexp = "(?i)^(LOST|FOUND)$", message = "type 仅支持 LOST/FOUND")
    private String type;

    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最长100字")
    private String title;

    /** 纯文本描述（服务端 jsoup 清洗后入库；认领核验与检索的正文事实源） */
    @NotBlank(message = "描述不能为空")
    @Size(max = 5000, message = "描述最长5000字")
    private String description;

    @NotNull(message = "分类不能为空")
    private Long categoryId;

    /** 校区（campus_area level=1，可空——只填自由文本地点的松散场景） */
    private Long campusId;

    /** 楼栋（campus_area level=2；给定时必须属于所选校区） */
    private Long buildingId;

    @Size(max = 200, message = "位置描述最长200字")
    private String locationText;

    /** 丢失/拾获时间（相似匹配时间窗要素；不允许未来时间，Service 校验） */
    @NotNull(message = "发生时间不能为空")
    private LocalDateTime happenedAt;

    /** 联系方式（仅登录用户可见——可见性矩阵；未登录回 null 不泄露） */
    @Size(max = 100, message = "联系方式最长100字")
    private String contact;

    /** FOUND 件必填：物主核验问题（认领流程的地基约束，缺失 2003） */
    @Size(max = 200, message = "核验问题最长200字")
    private String verifyQuestion;

    /** 图片 URL 列表（先经 POST /api/items/{id}/images 或发布口 attachments 通道；
     *  本期 save 请求直接携带已上传 URL 集合，上限 6 张 Service 钳制） */
    private List<String> imageUrls;

    /** 目标状态：null=草稿(0)；1=直接发布（仅 item_audit=0 时允许，见 Service） */
    private Integer status;

    /** 乐观锁版本：更新必须回传（缺失 400，冲突 2004——博客 M3 同款编辑防覆盖契约） */
    private Integer version;
}

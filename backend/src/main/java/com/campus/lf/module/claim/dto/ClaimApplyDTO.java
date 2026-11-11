package com.campus.lf.module.claim.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交认领申请请求体（POST /api/items/{id}/claims）。
 *
 * <p>answer 是认领核验的核心载荷：对应 FOUND 件的 verify_question，
 * 发布者据此判断"是不是真失主"。evidence_note 是补充自述（可选），
 * 仲裁场景管理员会连 answer+evidence 一起看。
 */
@Data
public class ClaimApplyDTO {

    /** 核验答案（必填，对齐 verify_question） */
    @NotBlank(message = "请填写核验答案")
    @Size(max = 500, message = "答案最长500字")
    private String answer;

    /** 补充凭证自述（可选） */
    @Size(max = 500, message = "凭证说明最长500字")
    private String evidenceNote;
}

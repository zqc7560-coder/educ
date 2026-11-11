package com.campus.lf.module.claim.vo;

import com.campus.lf.module.item.vo.ItemVO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 认领单出参。
 *
 * <p>视角化字段：answer 仅物品发布者（核验方）与仲裁管理员可见——申请人自己
 * 提交过的能看到；claimantContact 对发布者可见（约定交接用）。
 */
@Data
@Builder
public class ClaimVO {

    private Long id;
    private Long itemId;
    /** 认领申请人 */
    private Long claimantId;
    private String claimantNickname;
    /** 核验答案（发布者/管理员可见，无关者为 null） */
    private String answer;
    private String evidenceNote;
    /** 状态机取值见类注释/ClaimService */
    private Integer status;
    private String rejectReason;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;
    /** 乐观锁版本：发布者核验/确认归还时回传（防重复归还竞态） */
    private Integer version;
}

package com.campus.lf.module.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物品信息实体（LOST 寻物启事 / FOUND 招领启事统一表）—— M0 先行最小版。
 *
 * <p>设计依据《开发方案.md》§3.1：type 区分两向，状态机
 * 0 草稿(预留)/待审核 → 1 已发布 → 2 已下架，逻辑删除 + 乐观锁。
 * M1 落 18 表全量 DDL 时本实体与表结构做机械比对（列双向核对，博客 M1 手法）。
 */
@Data
@com.baomidou.mybatisplus.annotation.TableName("lost_item")
public class LostItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** LOST=寻物启事 / FOUND=招领启事（字符串枚举，DDL 层 CHECK 约束兜底） */
    private String type;

    /** 发布者用户 id */
    private Long userId;

    private String title;

    /** 纯文本描述（服务端 jsoup 清洗后入库，前端转义渲染，XSS 双防线） */
    private String description;

    /** 分类字典 id（category 表） */
    private Long categoryId;

    /** 事件发生时间（失物=丢失时间，拾物=拾获时间）——相似匹配时间窗要素 */
    private LocalDateTime happenedAt;

    /** 校区/楼栋（campus_area 二级），可空（描述型地点可只填 locationText） */
    private Long campusId;
    private Long buildingId;

    /** 具体位置自由文本（"三号楼门口快递柜"） */
    private String locationText;

    /** 联系方式（仅登录后可见——可见性矩阵 M3 落地） */
    private String contact;

    /** FOUND 件必填：物主核验问题（认领流程的地基约束，BizCode 2003） */
    private String verifyQuestion;

    /** 状态：0=待审核 1=已发布 2=已下架（物归原主/过期/违规） */
    private Integer status;

    /** 下架理由（M4 归还自动置 2 时写"已归还"；违规下架必填） */
    private String offlineReason;

    /** 冗余计数：详情页浏览（Redis 计数定时回写，M9） */
    private Integer viewCount;

    /** 冗余计数：在途+历史认领申请数 */
    private Integer claimCount;

    /** 冗余计数：收藏数（user_favorite target=ITEM 增删同事务结算） */
    private Integer likeCount;

    /** 置顶/加急（管理员治理，列表恒前排） */
    private Integer topFlag;

    /** 发布时间：首次进入已发布态写入，此后不变（排序基准） */
    private LocalDateTime publishedAt;

    /** 乐观锁版本（编辑并发防覆盖，2004） */
    @Version
    private Integer version;

    /** 逻辑删除位（回收站/审计语义：物理行保留，查询由 MP 自动拼 deleted=0） */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    private LocalDateTime createdAt;

    /** 更新时间：MySQL ON UPDATE CURRENT_TIMESTAMP 维护 */
    private LocalDateTime updatedAt;
}

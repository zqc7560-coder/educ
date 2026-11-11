package com.campus.lf.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统参数 —— schema.sql §20。治理旋钮（审核开关/每日上限/超时阈值）；config_type 驱动值校验；editable=0 的系统键接口禁改。
 */
@Data
@TableName("sys_config")
public class SysConfig {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 键名（uk_config_key） */
    private String configKey;

    /** 键值（类型化解释见 configType） */
    private String configValue;

    /** STRING/NUMBER/BOOL */
    private String configType;

    /** 参数说明 */
    private String description;

    /** 0=系统键不可经接口修改 */
    private Integer editable;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

    /** 逻辑删除位：@TableLogic，查询由 MP 自动拼 deleted=0 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /** 更新时间：MySQL ON UPDATE CURRENT_TIMESTAMP 维护 */
    private LocalDateTime updatedAt;
}

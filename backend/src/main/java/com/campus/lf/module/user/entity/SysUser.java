package com.campus.lf.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体 —— schema.sql §1。学号即登录凭据；status 三态承载封禁/禁言。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 主键：数据库自增（全表统一纪律：关联表亦 id 主键+唯一键表达组合约束——MP 单主键 API 前提） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学号（登录名，全校唯一，uk_student_no） */
    private String studentNo;

    /** BCrypt 散列（绝不存明文；红线扫描对象） */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 邮箱（uk_email） */
    private String email;

    /** 联系电话（个人资料，认领联系优先展示） */
    private String phone;

    /** 头像 URL */
    private String avatar;

    /** 1正常 0封禁 1003 执法 2禁言（只拦写口不拦读） */
    private Integer status;

    /** 主角色 id（简化字段；多角色真相在 sys_user_role） */
    private Long roleId;

    /** 最后登录时间（登录成功刷新） */
    private LocalDateTime lastLoginAt;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;

    /** 逻辑删除位：@TableLogic，查询由 MP 自动拼 deleted=0 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /** 更新时间：MySQL ON UPDATE CURRENT_TIMESTAMP 维护 */
    private LocalDateTime updatedAt;
}

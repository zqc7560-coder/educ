package com.campus.lf.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求体（POST /api/auth/login）。
 * 只有两字段：密码强度等规则登录时不再校验——历史账号口令策略可能不同，
 * 校验放在注册/改密即可，登录只做"对得上/对不上"（防枚举的统一 1002 文案）。
 */
@Data
public class LoginDTO {

    /** 学号（本期不支持邮箱登录：登录名与可换绑的邮箱解耦，避免两套钥匙的查重复杂度） */
    @NotBlank(message = "学号不能为空")
    private String studentNo;

    /** 明文密码：matches 校验后即丢弃 */
    @NotBlank(message = "密码不能为空")
    private String password;
}

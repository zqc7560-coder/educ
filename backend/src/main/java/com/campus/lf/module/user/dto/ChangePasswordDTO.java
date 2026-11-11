package com.campus.lf.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 修改密码请求体（PUT /api/users/password）。
 *
 * <p>改密成功后服务端拉黑旧 jti（当前会话）并抬用户级失效水位（全端下线），
 * 新密码强度规则与注册一致，且 Service 禁止与旧密码相同（BCrypt 单向，
 * 用 matches(新密码, 旧散列) 判定）。
 */
@Data
public class ChangePasswordDTO {

    /** 旧密码：验证"确实是自己"，防止令牌被盗后改密锁死账号 */
    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    /** 新密码：强度规则与注册一致（≥8 位含字母数字） */
    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "新密码须≥8位且包含字母和数字")
    private String newPassword;
}

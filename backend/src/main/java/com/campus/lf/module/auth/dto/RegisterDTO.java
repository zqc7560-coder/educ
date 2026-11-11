package com.campus.lf.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体（POST /api/auth/register）。
 *
 * <p>校验规则声明在 DTO 而不是散在 Service 手写 if：
 * 1) @Valid 失败由全局异常处理器统一转 400 字段明细；
 * 2.）Knife4j 文档能自动渲染约束，前后端契约一页可见。
 *
 * <p>学号规则取宽容形态（4~20 位字母数字）：各校学号位数不一（纯数字 8~14 位、
 * 含字母前缀等），过严的正则会把真实用户挡在门外；唯一性由 uk_student_no 兜底，
 * 强度治理交给口令策略。
 */
@Data
public class RegisterDTO {

    /** 学号（登录名，全校唯一） */
    @NotBlank(message = "学号不能为空")
    @Pattern(regexp = "^[0-9A-Za-z]{4,20}$", message = "学号须为4~20位字母或数字")
    private String studentNo;

    /**
     * 明文密码：仅存在于本次请求内存，Service 校验强度后立刻 BCrypt 散列入库，
     * 绝不出现在任何 VO/日志。≥8 位且必须同时含字母与数字（前瞻断言实现）。
     */
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "密码须≥8位且包含字母和数字")
    private String password;

    /** 昵称：允许重复（区别于学号唯一性），1~30 字对齐列长 VARCHAR(30) */
    @NotBlank(message = "昵称不能为空")
    @Size(max = 30, message = "昵称最长30字")
    private String nickname;

    /** 邮箱：唯一（uk_email），认领通知等找回通道 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长100字符")
    private String email;
}

package com.campus.lf.module.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料请求体（PUT /api/users/profile）。
 *
 * <p>字段白名单式 DTO：只开 nickname/email/phone 三个口，角色、状态、密码、id
 * 从结构上就无法从请求体注入（防"批量赋值"越权改自己为管理员的经典漏洞）。
 * 学号 studentNo 不在白名单——它是登录身份锚点，换绑学号=换人，禁止自助修改。
 */
@Data
public class ProfileUpdateDTO {

    /** 昵称：1~30 字（对齐 sys_user.nickname VARCHAR(30)） */
    @NotBlank(message = "昵称不能为空")
    @Size(max = 30, message = "昵称最长30字")
    private String nickname;

    /** 邮箱：换绑时 Service 查重转 1102 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长100字符")
    private String email;

    /** 联系电话：可空（列 VARCHAR(20)，认领联系优先展示） */
    @Size(max = 20, message = "电话最长20字符")
    private String phone;
}

package org.example.xqy1._026_silver_residence.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserRegistrationRequest(
        @NotBlank(message = "请输入用户名")
        @Size(min = 2, max = 64, message = "用户名长度应为 2 到 64 位")
        String username,
        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 128, message = "密码长度应为 6 到 128 位")
        String password,
        @NotBlank(message = "请输入邮箱")
        @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱长度不能超过 254 位")
        String email,
        @NotBlank(message = "请输入手机号")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone
) {
}

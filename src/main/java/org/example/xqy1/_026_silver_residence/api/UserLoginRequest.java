package org.example.xqy1._026_silver_residence.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserLoginRequest(
        @NotBlank(message = "请输入用户名")
        @Size(max = 64, message = "用户名长度不能超过 64 位")
        String username,
        @NotBlank(message = "请输入密码")
        @Size(max = 128, message = "密码长度不能超过 128 位")
        String password
) {
}

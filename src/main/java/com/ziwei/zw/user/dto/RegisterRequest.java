package com.ziwei.zw.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 自助注册：注册即建一个新租户，注册人成为该租户的 ADMIN。
 */
public record RegisterRequest(
        @NotBlank(message = "不能为空") @Size(min = 3, max = 64, message = "长度 3~64") String username,
        @NotBlank(message = "不能为空") @Size(min = 6, max = 64, message = "长度 6~64") String password,
        String nickname) {
}

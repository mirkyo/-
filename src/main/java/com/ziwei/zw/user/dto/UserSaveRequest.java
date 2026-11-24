package com.ziwei.zw.user.dto;

/**
 * 管理员创建 / 修改用户。
 */
public record UserSaveRequest(
        String username,
        String password,
        String nickname,
        String email,
        /** SUPER_ADMIN / ADMIN / USER */
        String role,
        /** 0启用 1禁用 */
        Integer status) {
}

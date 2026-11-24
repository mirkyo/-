package com.ziwei.zw.user.dto;

import com.ziwei.zw.user.User;

import java.time.LocalDateTime;

public record UserVO(
        Long id,
        Long tenantId,
        String username,
        String nickname,
        String email,
        String avatar,
        String role,
        Integer status,
        LocalDateTime lastLoginAt,
        LocalDateTime createTime) {

    public static UserVO of(User u) {
        return new UserVO(u.getId(), u.getTenantId(), u.getUsername(), u.getNickname(),
                u.getEmail(), u.getAvatar(), u.getRole(), u.getStatus(),
                u.getLastLoginAt(), u.getCreateTime());
    }
}

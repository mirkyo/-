package com.ziwei.zw.user.dto;

public record AuthResponse(String token, long expiresIn, UserVO user) {
}

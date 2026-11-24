package com.ziwei.zw.user;

import com.ziwei.zw.common.R;
import com.ziwei.zw.user.dto.AuthResponse;
import com.ziwei.zw.user.dto.LoginRequest;
import com.ziwei.zw.user.dto.RegisterRequest;
import com.ziwei.zw.user.dto.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req));
    }

    @PostMapping("/register")
    public R<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return R.ok(authService.register(req));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        // 无状态 JWT：前端丢掉 token 即可
        return R.ok();
    }

    @GetMapping("/me")
    public R<UserVO> me() {
        return R.ok(authService.me());
    }
}

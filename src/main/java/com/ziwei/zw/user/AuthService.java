package com.ziwei.zw.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ziwei.zw.common.BizException;
import com.ziwei.zw.common.TenantContext;
import com.ziwei.zw.config.ZwProperties;
import com.ziwei.zw.security.JwtTokenProvider;
import com.ziwei.zw.user.dto.AuthResponse;
import com.ziwei.zw.user.dto.LoginRequest;
import com.ziwei.zw.user.dto.RegisterRequest;
import com.ziwei.zw.user.dto.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 登录 / 注册 / 当前用户。
 *
 * <p>已去掉多租户：注册用户统一落到 tenant_id = 1（该列保留仅兼容历史数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final ZwProperties props;

    public AuthResponse login(LoginRequest req) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.username()));
        if (user == null) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {
            throw BizException.forbidden("账号已被锁定，请稍后再试");
        }
        if (user.getStatus() != null && user.getStatus() == 1) {
            throw BizException.forbidden("账号已被禁用");
        }
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            int fail = (user.getFailCount() == null ? 0 : user.getFailCount()) + 1;
            user.setFailCount(fail);
            if (fail >= 5) {
                user.setLockUntil(LocalDateTime.now().plusMinutes(15));
                log.warn("[auth] 用户 {} 连续失败 {} 次，锁定 15 分钟", user.getUsername(), fail);
            }
            userMapper.updateById(user);
            throw BizException.unauthorized("用户名或密码错误");
        }

        user.setFailCount(0);
        user.setLockUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        String token = tokenProvider.create(user.getId(), user.getTenantId(),
                user.getUsername(), user.getRole());
        log.info("[auth] 登录成功 userId={} tenantId={} role={}",
                user.getId(), user.getTenantId(), user.getRole());
        return new AuthResponse(token, tokenProvider.getExpireMillis() / 1000, UserVO.of(user));
    }

    /**
     * 自助注册：建租户 → 建用户（该租户 ADMIN）→ 直接签发 token。
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthResponse register(RegisterRequest req) {
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.username()));
        if (exists != null && exists > 0) {
            throw BizException.badRequest("用户名已存在：" + req.username());
        }


        User user = new User();
        user.setTenantId(1L);
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setNickname(req.nickname() == null || req.nickname().isBlank() ? req.username() : req.nickname());
        user.setEmail("");
        user.setAvatar("");
        user.setStatus(0);
        user.setRole("ADMIN");
        user.setFailCount(0);
        userMapper.insert(user);

        log.info("[auth] 注册成功 username={}", user.getUsername());
        String token = tokenProvider.create(user.getId(), user.getTenantId(),
                user.getUsername(), user.getRole());
        return new AuthResponse(token, tokenProvider.getExpireMillis() / 1000, UserVO.of(user));
    }

    public UserVO me() {
        User user = userMapper.selectById(TenantContext.requireUserId());
        if (user == null) {
            throw BizException.unauthorized("用户不存在");
        }
        return UserVO.of(user);
    }

    public User requireCurrentUser() {
        User user = userMapper.selectById(TenantContext.requireUserId());
        if (user == null) {
            throw BizException.unauthorized("用户不存在");
        }
        return user;
    }

    public ZwProperties.Init initConfig() {
        return props.getInit();
    }
}

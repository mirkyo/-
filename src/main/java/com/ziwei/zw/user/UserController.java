package com.ziwei.zw.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ziwei.zw.common.BizException;
import com.ziwei.zw.common.R;
import com.ziwei.zw.common.TenantContext;
import com.ziwei.zw.user.dto.UserSaveRequest;
import com.ziwei.zw.user.dto.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 用户管理（租户 ADMIN 及以上）。
 *
 * <p>租户拦截器会自动把查询限制在当前租户内；创建时显式写入当前租户。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /** 可分配的角色白名单。 */
    private static final Set<String> ASSIGNABLE_ROLES = Set.of("SUPER_ADMIN", "ADMIN", "USER");

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        Page<User> p = userMapper.selectPage(new Page<>(page + 1L, size),
                new LambdaQueryWrapper<User>().orderByDesc(User::getId));
        return R.ok(Map.of(
                "list", p.getRecords().stream().map(UserVO::of).toList(),
                "total", p.getTotal(),
                "page", page,
                "size", size));
    }

    @PostMapping
    public R<Long> create(@RequestBody UserSaveRequest req) {
        if (req.username() == null || req.username().isBlank()) {
            throw BizException.badRequest("用户名不能为空");
        }
        if (req.password() == null || req.password().length() < 6) {
            throw BizException.badRequest("密码至少 6 位");
        }
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.username()));
        if (exists != null && exists > 0) {
            throw BizException.badRequest("用户名已存在：" + req.username());
        }
        User user = new User();
        user.setTenantId(TenantContext.getTenantId());
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setNickname(req.nickname() == null ? req.username() : req.nickname());
        user.setEmail(req.email() == null ? "" : req.email());
        user.setAvatar("");
        user.setStatus(req.status() == null ? 0 : req.status());
        user.setRole(requireAssignableRole(req.role(), "USER"));
        user.setFailCount(0);
        userMapper.insert(user);
        log.info("[user] 新建用户 id={} username={}", user.getId(), user.getUsername());
        return R.ok(user.getId());
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody UserSaveRequest req) {
        User user = requireInTenant(id);
        if (req.nickname() != null) {
            user.setNickname(req.nickname());
        }
        if (req.email() != null) {
            user.setEmail(req.email());
        }
        if (req.role() != null && !req.role().isBlank()) {
            user.setRole(requireAssignableRole(req.role(), user.getRole()));
        }
        if (req.status() != null) {
            user.setStatus(req.status());
        }
        if (req.password() != null && !req.password().isBlank()) {
            if (req.password().length() < 6) {
                throw BizException.badRequest("密码至少 6 位");
            }
            user.setPassword(passwordEncoder.encode(req.password()));
        }
        userMapper.updateById(user);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        if (id.equals(TenantContext.getUserId())) {
            throw BizException.badRequest("不能删除自己");
        }
        requireInTenant(id);
        // 物理删：username 唯一键不能被逻辑删除占位
        userMapper.deletePhysically(id);
        log.info("[user] 删除用户 id={}", id);
        return R.ok();
    }

    /**
     * 角色白名单 + 防提权：只有超级管理员能分配 SUPER_ADMIN。
     * 不然租户管理员构造一个 role=SUPER_ADMIN 的请求，就能拿到跨租户权限。
     */
    private String requireAssignableRole(String requested, String fallback) {
        String role = (requested == null || requested.isBlank()) ? fallback : requested.trim().toUpperCase();
        if (!ASSIGNABLE_ROLES.contains(role)) {
            throw BizException.badRequest("非法角色：" + requested);
        }
        if ("SUPER_ADMIN".equals(role) && !TenantContext.isSuperAdmin()) {
            throw BizException.forbidden("只有超级管理员可以分配 SUPER_ADMIN");
        }
        return role;
    }

    private User requireInTenant(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            // 租户拦截器已经过滤了跨租户数据，查不到即视为不存在
            throw BizException.notFound("用户不存在");
        }
        return user;
    }
}

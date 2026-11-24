package com.ziwei.zw.common;

/**
 * 当前请求的租户 / 用户上下文。
 *
 * <p>由 {@code TenantContextFilter} 在鉴权之后写入，MyBatis-Plus 的租户拦截器从这里取
 * {@code tenantId} 自动给 SQL 拼条件。线程级，请求结束务必 {@link #clear()}。
 *
 * <p>约定：{@code tenantId == null} 时租户拦截器**不拼条件**（登录、注册这类尚未鉴权的请求）。
 * 所以所有业务接口都必须走鉴权，否则会绕过隔离。
 */
public final class TenantContext {

    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId, Long userId, String role) {
        TENANT.set(tenantId);
        USER.set(userId);
        ROLE.set(role);
    }

    public static Long getTenantId() {
        return TENANT.get();
    }

    public static Long getUserId() {
        return USER.get();
    }

    /** 当前用户角色（SUPER_ADMIN / ADMIN / USER）；未鉴权时为 null。 */
    public static String currentRole() {
        return ROLE.get();
    }

    public static boolean isSuperAdmin() {
        return "SUPER_ADMIN".equals(ROLE.get());
    }

    public static Long requireUserId() {
        Long userId = USER.get();
        if (userId == null) {
            throw BizException.unauthorized("未认证");
        }
        return userId;
    }

    public static void clear() {
        TENANT.remove();
        USER.remove();
        ROLE.remove();
    }
}

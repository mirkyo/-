package com.ziwei.zw.security;

/**
 * 放在 {@code Authentication.details} 里的登录主体信息。
 *
 * <p>业务侧统一从 {@link com.ziwei.zw.common.TenantContext} 取，
 * 这里保留是为了兼容 Spring Security 的 Authentication 结构。
 */
public record LoginUser(Long tenantId, Long userId, String username, String role) {
}

package com.ziwei.zw.security;

import com.ziwei.zw.common.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 鉴权过滤器。
 *
 * <p>校验通过后做两件事：
 * <ol>
 *   <li>把 {@link LoginUser} 放进 {@code Authentication.details}；</li>
 *   <li>写入 {@link TenantContext}，供 MyBatis-Plus 租户拦截器读取。</li>
 * </ol>
 * 请求结束务必清理 ThreadLocal。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token != null) {
                LoginUser loginUser = tokenProvider.parse(token);
                if (loginUser != null && loginUser.userId() != null) {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            loginUser.username(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + safeRole(loginUser.role()))));
                    auth.setDetails(loginUser);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    TenantContext.set(loginUser.tenantId(), loginUser.userId(), safeRole(loginUser.role()));
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * SSE 的 Flux 正常结束时会再走一次 async dispatch，默认不重跑过滤器 ⇒ 那次 dispatch 没有认证信息，
     * Spring Security 会当成未认证去写 401，但响应已经提交了，于是刷一堆
     * 「Unable to handle the Spring Security Exception because the response is already committed」。
     * 本过滤器是无状态的（每次从 header 读 token），重跑没有副作用，所以让它跑。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        // 兼容 SSE：浏览器 EventSource 不能带自定义头，允许 query 参数传 token
        String queryToken = request.getParameter("token");
        return (queryToken == null || queryToken.isBlank()) ? null : queryToken;
    }

    private String safeRole(String role) {
        return (role == null || role.isBlank()) ? "USER" : role;
    }
}

package com.ziwei.zw.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ziwei.zw.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置：无状态 JWT，按角色分级授权。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 允许同源 iframe（诊断页要用；跨站仍然禁止）
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 静态资源与页面
                        .requestMatchers("/", "/index.html", "/favicon.ico",
                                "/html/**", "/js/**", "/css/**", "/fonts/**").permitAll()
                        // 登录 / 健康检查（公开）
                        .requestMatchers("/api/auth/login", "/api/health").permitAll()
                        // 注册入口已关闭：账号只能由管理员在「管理」页创建
                        .requestMatchers("/api/auth/register").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .requestMatchers("/api/assets/**").permitAll()
                        // 租户管理仅超管
                        .requestMatchers("/api/admin/tenant/**").hasRole("SUPER_ADMIN")
                        // 用户与技能管理：租户管理员及以上
                        .requestMatchers("/api/admin/user/**").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        // 技能是平台级的（全租户共用一份 skills 目录），增删改都限管理员
                        .requestMatchers(HttpMethod.DELETE, "/api/skills/**").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/skills/upload").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/skills/**").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, resp, e) -> write(resp, 401, "未认证或登录已过期"))
                        .accessDeniedHandler((req, resp, e) -> write(resp, 403, "没有权限执行该操作")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void write(jakarta.servlet.http.HttpServletResponse resp, int code, String msg) throws java.io.IOException {
        resp.setStatus(code);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(objectMapper.writeValueAsString(R.fail(code, msg)));
    }
}

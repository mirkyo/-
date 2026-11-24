package com.ziwei.zw.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTML 页面禁止缓存。
 *
 * <p>前端页面里的内联登录闸门、资源版本号都写在 HTML 上，
 * 一旦 HTML 被浏览器缓存，用户就会一直跑旧页面（表现为"改了不生效""跳不回登录页"）。
 * 这里对 .html 和根路径统一加 no-store。
 */
@Component
public class NoCacheHtmlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && (uri.endsWith(".html") || "/".equals(uri))) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
        }
        chain.doFilter(request, response);
    }
}

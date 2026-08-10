package com.idolradar.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 为每个 servlet 请求添加响应安全头和日志安全的关联 ID。 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        // 调用方提供的 ID 写入 MDC 前须受限，防止日志注入和超长标签。
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        try {
            response.setHeader("X-Request-Id", requestId);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Cache-Control", "no-store");
            // 管理页图表需要动态内联样式；脚本仍严格限制为同源文件，禁止内联执行。
            response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; "
                    + "style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'; "
                    + "base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            filterChain.doFilter(request, response);
        } finally {
            // servlet 线程会复用；清理 MDC，避免 request ID 泄漏到其他请求日志。
            MDC.remove("requestId");
        }
    }
}

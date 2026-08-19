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
    /** Vite 只把带内容指纹的产物写进这个目录；入口 index.html 不在其中。 */
    private static final String FINGERPRINTED_ASSET_PREFIX = "/admin/assets/";

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
            // 管理后台与业务接口都不应进入搜索引擎索引。
            response.setHeader("X-Robots-Tag", "noindex, nofollow");
            // 指纹产物改名即换 URL，可长缓存；入口 HTML 必须不缓存，
            // 否则发版后浏览器会拿旧 HTML 去请求已删除的旧指纹资源，表现为间歇性白屏。
            response.setHeader(
                    "Cache-Control",
                    request.getRequestURI().startsWith(FINGERPRINTED_ASSET_PREFIX)
                            ? "public, max-age=31536000, immutable"
                            : "no-store");
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

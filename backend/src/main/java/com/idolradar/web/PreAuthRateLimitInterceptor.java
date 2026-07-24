package com.idolradar.web;

import com.idolradar.api.AppException;
import com.idolradar.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 认证前按 IP 限流，避免 PostgreSQL 或 WeChat 容量被未认证流量消耗。 */
@Component
public class PreAuthRateLimitInterceptor implements HandlerInterceptor {
    private final DistributedRateLimiter rateLimiter;
    private final RateLimitProperties properties;

    public PreAuthRateLimitInterceptor(
            DistributedRateLimiter rateLimiter,
            RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        boolean login = "/v1/auth/wechat/login".equals(request.getRequestURI());
        int maximum = login ? properties.loginLimit() : properties.ipLimit();
        // 使用容器解析出的对端地址；可信代理处理在服务器边界配置。
        String remoteAddress = request.getRemoteAddr();
        String subject = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
        String scope = login ? "login-ip" : "api-ip";
        if (!rateLimiter.allow(scope, subject, maximum, properties.window())) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求太频繁，请稍后再试");
        }
        return true;
    }
}

package com.idolradar.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.idolradar.api.AppException;
import com.idolradar.auth.AuthInterceptor;
import com.idolradar.auth.AuthService;
import com.idolradar.config.RateLimitProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 认证后按 openId 限流，防止同一用户通过更换 IP 绕过限制。 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final DistributedRateLimiter rateLimiter;
    private final RateLimitProperties properties;

    public RateLimitInterceptor(DistributedRateLimiter rateLimiter, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Limit limit = limitFor(request);
        String subject = subject(request);
        if (!rateLimiter.allow(limit.scope(), subject, limit.maximum(), properties.window())) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求太频繁，请稍后再试");
        }
        return true;
    }

    private Limit limitFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("/v1/me/subscriptions".equals(path)) {
            return new Limit("subscription", properties.subscriptionLimit());
        }
        return new Limit("api", properties.defaultLimit());
    }

    private static String subject(HttpServletRequest request) {
        Object identity = request.getAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE);
        if (identity instanceof AuthService.Identity authenticated) {
            return authenticated.openId();
        }
        // 拦截器顺序异常时拒绝请求；回退使用客户端输入会削弱隔离。
        throw new IllegalStateException("Authenticated rate limit requires identity");
    }

    private record Limit(String scope, int maximum) {
    }
}

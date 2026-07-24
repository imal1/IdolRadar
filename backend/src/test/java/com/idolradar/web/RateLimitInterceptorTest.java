package com.idolradar.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.idolradar.api.AppException;
import com.idolradar.auth.AuthInterceptor;
import com.idolradar.auth.AuthService;
import com.idolradar.config.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitInterceptorTest {

    @Test
    void loginUsesDedicatedDistributedLimit() {
        AtomicReference<Call> captured = new AtomicReference<>();
        DistributedRateLimiter limiter = (scope, subject, limit, window) -> {
            captured.set(new Call(scope, subject, limit, window));
            return true;
        };
        PreAuthRateLimitInterceptor interceptor = new PreAuthRateLimitInterceptor(
                limiter, new RateLimitProperties(120, 20, 12, 1_200, Duration.ofMinutes(1)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/wechat/login");
        request.setRemoteAddr("192.0.2.1");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(new Call("login-ip", "192.0.2.1", 20, Duration.ofMinutes(1)), captured.get());
    }

    @Test
    void rejectedRequestReturnsStableErrorCode() {
        DistributedRateLimiter limiter = (scope, subject, limit, window) -> false;
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                limiter, new RateLimitProperties(120, 20, 12, 1_200, Duration.ofMinutes(1)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/home");
        request.setAttribute(AuthInterceptor.IDENTITY_ATTRIBUTE, new AuthService.Identity(
                UUID.randomUUID(), "openid-1", Instant.now().plusSeconds(60)));

        AppException error = assertThrows(
                AppException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("RATE_LIMITED", error.code());
        assertEquals(429, error.status().value());
    }

    private record Call(String scope, String subject, int limit, Duration window) {
    }
}

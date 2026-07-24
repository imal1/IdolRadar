package com.idolradar.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 分布式限流阈值；构造时校验，防止零值或异常大值让保护失效。 */
@ConfigurationProperties("idolradar.rate-limit")
public record RateLimitProperties(
        Integer defaultLimit,
        Integer loginLimit,
        Integer subscriptionLimit,
        Integer ipLimit,
        Duration window) {

    public RateLimitProperties {
        defaultLimit = positive(defaultLimit, 120, "default-limit");
        loginLimit = positive(loginLimit, 20, "login-limit");
        subscriptionLimit = positive(subscriptionLimit, 12, "subscription-limit");
        ipLimit = positive(ipLimit, 1_200, "ip-limit");
        window = window == null ? Duration.ofMinutes(1) : window;
        if (window.compareTo(Duration.ofMillis(1)) < 0
                || window.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("idolradar.rate-limit.window must be between 1 ms and 1 hour");
        }
    }

    private static int positive(Integer value, int fallback, String name) {
        int resolved = value == null ? fallback : value;
        if (resolved < 1 || resolved > 100_000) {
            throw new IllegalArgumentException("idolradar.rate-limit." + name + " is invalid");
        }
        return resolved;
    }
}

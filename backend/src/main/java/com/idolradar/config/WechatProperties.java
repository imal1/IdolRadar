package com.idolradar.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 微信凭据、网关与超时配置；生产网关必须使用 HTTPS。 */
@ConfigurationProperties("idolradar.wechat")
public record WechatProperties(
        String appId,
        String appSecret,
        URI apiBaseUrl,
        Duration timeout) {

    public WechatProperties {
        appId = appId == null ? "" : appId.trim();
        appSecret = appSecret == null ? "" : appSecret.trim();
        apiBaseUrl = apiBaseUrl == null ? URI.create("https://api.weixin.qq.com") : apiBaseUrl;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        if (!"https".equalsIgnoreCase(apiBaseUrl.getScheme())
                && !"http".equalsIgnoreCase(apiBaseUrl.getScheme())) {
            throw new IllegalArgumentException("idolradar.wechat.api-base-url must use http or https");
        }
        if (timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("idolradar.wechat.timeout must be between 1 ms and 30 seconds");
        }
    }

    public void validateForApi() {
        if (appId.isBlank()) {
            throw new IllegalStateException("idolradar.wechat.app-id is required in api mode");
        }
        if (appSecret.isBlank()) {
            throw new IllegalStateException("idolradar.wechat.app-secret is required in api mode");
        }
        // HTTP 仅用于本机 mock server，避免生产凭据经明文链路发送。
        if ("http".equalsIgnoreCase(apiBaseUrl.getScheme()) && !isLoopback(apiBaseUrl.getHost())) {
            throw new IllegalStateException("idolradar.wechat.api-base-url must use HTTPS outside local tests");
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String normalized = host.replace("[", "").replace("]", "").toLowerCase(java.util.Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized);
    }
}

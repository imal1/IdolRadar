package com.idolradar.worker;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** worker 运行参数与安全上限；每次运行前统一校验。 */
@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
@ConfigurationProperties(prefix = "idolradar.worker")
public class WorkerProperties {
    private String wechatAppId = "";
    private String wechatAppSecret = "";
    private URI wechatApiBaseUrl = URI.create("https://api.weixin.qq.com");
    private String subscribeTemplateId = "";
    private String miniprogramState = "formal";
    private Duration rssTimeout = Duration.ofSeconds(10);
    private int rssMaxResponseBytes = 2 * 1024 * 1024;
    private int rssMaxRedirects = 3;
    private int rssMaxEntriesPerSource = 100;
    private int rssSourceConcurrency = 4;
    private int notificationConcurrency = 8;
    private Duration notificationLease = Duration.ofMinutes(15);
    private int notificationMaxAttempts = 5;
    private Duration notificationRetryBase = Duration.ofMinutes(1);

    public void validateForRun() {
        requireText(wechatAppId, "idolradar.worker.wechat-app-id");
        requireText(wechatAppSecret, "idolradar.worker.wechat-app-secret");
        requireText(subscribeTemplateId, "idolradar.worker.subscribe-template-id");
        if (!"https".equalsIgnoreCase(wechatApiBaseUrl.getScheme())) {
            throw new IllegalStateException("idolradar.worker.wechat-api-base-url must use HTTPS");
        }
        if (!java.util.Set.of("developer", "trial", "formal").contains(miniprogramState)) {
            throw new IllegalStateException("idolradar.worker.miniprogram-state is invalid");
        }
        if (rssTimeout.compareTo(Duration.ofMillis(1)) < 0
                || notificationLease.compareTo(Duration.ofSeconds(1)) < 0
                || notificationRetryBase.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalStateException("Worker durations must be positive");
        }
        if (rssMaxResponseBytes < 1 || rssMaxResponseBytes > 5 * 1024 * 1024
                || rssMaxRedirects < 0 || rssMaxRedirects > 5
                || rssMaxEntriesPerSource < 1 || rssMaxEntriesPerSource > 200
                || rssSourceConcurrency < 1 || rssSourceConcurrency > 32
                || notificationConcurrency < 1 || notificationConcurrency > 64
                || notificationMaxAttempts < 1 || notificationMaxAttempts > 20) {
            throw new IllegalStateException("Worker numeric configuration is invalid");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    }

    public String getWechatAppId() { return wechatAppId; }
    public void setWechatAppId(String value) { this.wechatAppId = value; }
    public String getWechatAppSecret() { return wechatAppSecret; }
    public void setWechatAppSecret(String value) { this.wechatAppSecret = value; }
    public URI getWechatApiBaseUrl() { return wechatApiBaseUrl; }
    public void setWechatApiBaseUrl(URI value) { this.wechatApiBaseUrl = value; }
    public String getSubscribeTemplateId() { return subscribeTemplateId; }
    public void setSubscribeTemplateId(String value) { this.subscribeTemplateId = value; }
    public String getMiniprogramState() { return miniprogramState; }
    public void setMiniprogramState(String value) { this.miniprogramState = value; }
    public Duration getRssTimeout() { return rssTimeout; }
    public void setRssTimeout(Duration value) { this.rssTimeout = value; }
    public int getRssMaxResponseBytes() { return rssMaxResponseBytes; }
    public void setRssMaxResponseBytes(int value) { this.rssMaxResponseBytes = value; }
    public int getRssMaxRedirects() { return rssMaxRedirects; }
    public void setRssMaxRedirects(int value) { this.rssMaxRedirects = value; }
    public int getRssMaxEntriesPerSource() { return rssMaxEntriesPerSource; }
    public void setRssMaxEntriesPerSource(int value) { this.rssMaxEntriesPerSource = value; }
    public int getRssSourceConcurrency() { return rssSourceConcurrency; }
    public void setRssSourceConcurrency(int value) { this.rssSourceConcurrency = value; }
    public int getNotificationConcurrency() { return notificationConcurrency; }
    public void setNotificationConcurrency(int value) { this.notificationConcurrency = value; }
    public Duration getNotificationLease() { return notificationLease; }
    public void setNotificationLease(Duration value) { this.notificationLease = value; }
    public int getNotificationMaxAttempts() { return notificationMaxAttempts; }
    public void setNotificationMaxAttempts(int value) { this.notificationMaxAttempts = value; }
    public Duration getNotificationRetryBase() { return notificationRetryBase; }
    public void setNotificationRetryBase(Duration value) { this.notificationRetryBase = value; }
}

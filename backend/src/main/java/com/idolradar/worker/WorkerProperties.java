package com.idolradar.worker;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** worker 运行参数与安全上限；每次运行前统一校验。 */
// 抓取链路组件不再按 app.mode 隔离：管理端手动验证也要走同一套下载与解析，两处实现会立刻分叉。
@Component
@ConfigurationProperties(prefix = "idolradar.worker")
public class WorkerProperties {
    private String wechatAppId = "";
    private String wechatAppSecret = "";
    private URI wechatApiBaseUrl = URI.create("https://api.weixin.qq.com");
    private String subscribeTemplateId = "";
    private String subscribeIdolField = "thing1";
    private String subscribeTitleField = "thing2";
    private String subscribeTimeField = "time3";
    private boolean notificationsEnabled = true;
    private String miniprogramState = "formal";
    private Duration rssTimeout = Duration.ofSeconds(10);
    private int rssMaxResponseBytes = 2 * 1024 * 1024;
    private int rssMaxRedirects = 3;
    private int rssMaxEntriesPerSource = 100;
    private int rssSourceConcurrency = 4;
    private List<URI> rssTrustedOrigins = new ArrayList<>();
    private int notificationConcurrency = 8;
    private Duration notificationLease = Duration.ofMinutes(15);
    private int notificationMaxAttempts = 5;
    private Duration notificationRetryBase = Duration.ofMinutes(1);
    private boolean scheduleEnabled = false;
    private Duration scheduleInterval = Duration.ofMinutes(30);
    private Duration scheduleInitialDelay = Duration.ofSeconds(5);

    /**
     * 只校验「组装一条订阅消息 payload」这件事真正读取的配置。
     *
     * <p>单独成一个方法，是为了让 API 容器的定向发送在发送前也能跑同一套断言：那条路径不经过
     * WorkerModeConfiguration 的启动门禁，而字段名格式写错（例如把 thing8 写成 foo）只会在微信侧
     * 被拒，返回的错误码指不回配置本身。
     *
     * <p>范围刻意只到 payload：微信 AppID/AppSecret 与 API base URL 由 API 侧的 WechatProperties
     * 提供，本类同名字段只用于启动时的跨容器一致性比对，发送路径根本不读；RSS 超时与并发同理无关。
     * 把它们放进来会让一次定向发送因为完全无关的配置项莫名失败。
     */
    public void validateForNotificationSend() {
        if (!notificationsEnabled) return;
        requireTemplateField(subscribeIdolField, "thing", "idolradar.worker.subscribe-idol-field");
        requireTemplateField(subscribeTitleField, "thing", "idolradar.worker.subscribe-title-field");
        requireTemplateField(subscribeTimeField, "time", "idolradar.worker.subscribe-time-field");
        if (subscribeIdolField.equals(subscribeTitleField)) {
            throw new IllegalStateException("WeChat subscribe template fields must be distinct");
        }
        requireMiniprogramState();
    }

    private void requireMiniprogramState() {
        if (!java.util.Set.of("developer", "trial", "formal").contains(miniprogramState)) {
            throw new IllegalStateException("idolradar.worker.miniprogram-state is invalid");
        }
    }

    public void validateForRun() {
        validateForNotificationSend();
        if (notificationsEnabled) {
            // 这几项只在 Worker 启动门禁里校验：它们服务于「Worker 与 API 身份必须一致」，
            // 而不是单条消息的组装。
            requireText(wechatAppId, "idolradar.worker.wechat-app-id");
            requireText(wechatAppSecret, "idolradar.worker.wechat-app-secret");
            requireText(subscribeTemplateId, "idolradar.worker.subscribe-template-id");
            if (!"https".equalsIgnoreCase(wechatApiBaseUrl.getScheme())) {
                throw new IllegalStateException("idolradar.worker.wechat-api-base-url must use HTTPS");
            }
        }
        // 关闭推送时 validateForNotificationSend 会提前返回，因此这里仍要独立校验一次。
        requireMiniprogramState();
        if (rssTimeout.compareTo(Duration.ofMillis(1)) < 0
                || notificationLease.compareTo(Duration.ofSeconds(1)) < 0
                || notificationRetryBase.compareTo(Duration.ofSeconds(1)) < 0
                || scheduleInterval.compareTo(Duration.ofMinutes(1)) < 0
                || scheduleInterval.compareTo(Duration.ofHours(24)) > 0
                || scheduleInitialDelay.isNegative()) {
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
        for (URI origin : rssTrustedOrigins) {
            if (!isHttpOrigin(origin)) {
                throw new IllegalStateException("idolradar.worker.rss-trusted-origins must contain origins");
            }
        }
    }

    private static boolean isHttpOrigin(URI origin) {
        if (origin == null || origin.getScheme() == null || origin.getHost() == null
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null) {
            return false;
        }
        String path = origin.getRawPath();
        return java.util.Set.of("http", "https").contains(origin.getScheme().toLowerCase())
                && (path == null || path.isBlank() || "/".equals(path));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    }

    /** 微信下发的字段序号由审核模板决定，启动前阻止错误类型进入真实 fanout。 */
    private static void requireTemplateField(String value, String type, String name) {
        if (value == null || !value.matches(type + "\\d+")) {
            throw new IllegalStateException(name + " must match " + type + "<number>");
        }
    }

    public String getWechatAppId() { return wechatAppId; }
    public void setWechatAppId(String value) { this.wechatAppId = value; }
    public String getWechatAppSecret() { return wechatAppSecret; }
    public void setWechatAppSecret(String value) { this.wechatAppSecret = value; }
    public URI getWechatApiBaseUrl() { return wechatApiBaseUrl; }
    public void setWechatApiBaseUrl(URI value) { this.wechatApiBaseUrl = value; }
    public String getSubscribeTemplateId() { return subscribeTemplateId; }
    public void setSubscribeTemplateId(String value) { this.subscribeTemplateId = value; }
    public String getSubscribeIdolField() { return subscribeIdolField; }
    public void setSubscribeIdolField(String value) { this.subscribeIdolField = value; }
    public String getSubscribeTitleField() { return subscribeTitleField; }
    public void setSubscribeTitleField(String value) { this.subscribeTitleField = value; }
    public String getSubscribeTimeField() { return subscribeTimeField; }
    public void setSubscribeTimeField(String value) { this.subscribeTimeField = value; }
    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean value) { this.notificationsEnabled = value; }
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
    public List<URI> getRssTrustedOrigins() { return List.copyOf(rssTrustedOrigins); }
    public void setRssTrustedOrigins(List<URI> value) {
        this.rssTrustedOrigins = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public int getNotificationConcurrency() { return notificationConcurrency; }
    public void setNotificationConcurrency(int value) { this.notificationConcurrency = value; }
    public Duration getNotificationLease() { return notificationLease; }
    public void setNotificationLease(Duration value) { this.notificationLease = value; }
    public int getNotificationMaxAttempts() { return notificationMaxAttempts; }
    public void setNotificationMaxAttempts(int value) { this.notificationMaxAttempts = value; }
    public Duration getNotificationRetryBase() { return notificationRetryBase; }
    public void setNotificationRetryBase(Duration value) { this.notificationRetryBase = value; }
    public boolean isScheduleEnabled() { return scheduleEnabled; }
    public void setScheduleEnabled(boolean value) { this.scheduleEnabled = value; }
    public Duration getScheduleInterval() { return scheduleInterval; }
    public void setScheduleInterval(Duration value) { this.scheduleInterval = value; }
    public Duration getScheduleInitialDelay() { return scheduleInitialDelay; }
    public void setScheduleInitialDelay(Duration value) { this.scheduleInitialDelay = value; }
}

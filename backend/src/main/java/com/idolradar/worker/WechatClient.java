package com.idolradar.worker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idolradar.config.WechatProperties;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 通过有界 HTTP 客户端调用 WeChat 订阅消息 API，并共享 access token。
 *
 * <p>token 在 Redis 中提前五分钟过期；JVM CompletableFuture 与带 owner token 的 Redis lock
 * 分别合并进程内、跨实例刷新。compare-and-delete 防止旧 owner 删除新 lock/token；
 * token 失效时仅删除匹配缓存，刷新后最多重发一次。
 */
@Component("workerWechatClient")
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class WechatClient implements WechatGateway {
    private static final java.util.Set<Integer> INVALID_TOKEN_CODES = java.util.Set.of(40001, 40014, 42001);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> DELETE_IF_VALUE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final WechatProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final CloseableHttpClient http;
    private final String tokenKey;
    private final String lockKey;
    private final AtomicReference<CompletableFuture<String>> tokenRequest = new AtomicReference<>();

    public WechatClient(WechatProperties properties, ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redis = redis;
        Timeout timeout = Timeout.ofMilliseconds(properties.timeout().toMillis());
        this.http = HttpClients.custom()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(timeout)
                        .setConnectionRequestTimeout(timeout)
                        .setResponseTimeout(timeout)
                        .setRedirectsEnabled(false)
                        .build())
                .build();
        String suffix = shortHash(properties.appId());
        this.tokenKey = "idolradar:wechat:access-token:" + suffix;
        this.lockKey = tokenKey + ":lock";
    }

    @Override
    public void sendSubscribeMessage(WorkerModels.SubscribeMessage message, Runnable beforeSend) {
        String accessToken = getAccessToken();
        HttpPost request = prepareSend(message, accessToken);
        beforeSend.run();
        JsonNode result = execute(request);
        int code = result.path("errcode").asInt(0);
        if (code == 0) return;
        if (INVALID_TOKEN_CODES.contains(code)) {
            invalidateToken(accessToken);
            JsonNode retried = execute(prepareSend(message, getAccessToken()));
            int retriedCode = retried.path("errcode").asInt(0);
            if (retriedCode == 0) return;
            throw new WechatException("微信订阅消息发送失败", retriedCode);
        }
        throw new WechatException("微信订阅消息发送失败", code);
    }

    String getAccessToken() {
        String cached = redis.opsForValue().get(tokenKey);
        if (cached != null && !cached.isBlank()) return cached;
        while (true) {
            CompletableFuture<String> current = tokenRequest.get();
            if (current != null) return join(current);
            CompletableFuture<String> created = new CompletableFuture<>();
            if (!tokenRequest.compareAndSet(null, created)) continue;
            try {
                String loaded = loadWithDistributedLock();
                created.complete(loaded);
                return loaded;
            } catch (Throwable error) {
                created.completeExceptionally(error);
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new WechatException("微信访问凭证获取失败", null, error);
            } finally {
                tokenRequest.compareAndSet(created, null);
            }
        }
    }

    private String loadWithDistributedLock() {
        String owner = UUID.randomUUID().toString();
        Duration lockDuration = properties.timeout().plusSeconds(5);
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, owner, lockDuration);
        if (Boolean.TRUE.equals(acquired)) {
            try {
                String cached = redis.opsForValue().get(tokenKey);
                if (cached != null && !cached.isBlank()) return cached;
                return requestAndCacheToken();
            } finally {
                redis.execute(UNLOCK_SCRIPT, List.of(lockKey), owner);
            }
        }

        long deadline = System.nanoTime() + lockDuration.toNanos();
        while (System.nanoTime() < deadline) {
            String cached = redis.opsForValue().get(tokenKey);
            if (cached != null && !cached.isBlank()) return cached;
            try {
                Thread.sleep(100);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new WechatException("微信访问凭证获取被中断", null, error);
            }
        }
        throw new WechatException("微信访问凭证获取超时", null);
    }

    private String requestAndCacheToken() {
        try {
            URI endpoint = new URIBuilder(properties.apiBaseUrl().resolve("/cgi-bin/token"))
                    .addParameter("grant_type", "client_credential")
                    .addParameter("appid", properties.appId())
                    .addParameter("secret", properties.appSecret())
                    .build();
            JsonNode result = execute(new HttpGet(endpoint));
            int code = result.path("errcode").asInt(0);
            String token = result.path("access_token").asText("");
            if (code != 0 || token.isBlank()) {
                throw new WechatException("微信访问凭证获取失败", code == 0 ? null : code);
            }
            long expiresIn = result.path("expires_in").asLong(7200);
            long safeSeconds = Math.max(60, expiresIn - 300);
            redis.opsForValue().set(tokenKey, token, Duration.ofSeconds(safeSeconds));
            return token;
        } catch (java.net.URISyntaxException error) {
            throw new WechatException("微信 API 地址无效", null, error);
        }
    }

    private HttpPost prepareSend(WorkerModels.SubscribeMessage message, String accessToken) {
        try {
            URI endpoint = new URIBuilder(properties.apiBaseUrl().resolve("/cgi-bin/message/subscribe/send"))
                    .addParameter("access_token", accessToken)
                    .build();
            HttpPost request = new HttpPost(endpoint);
            Map<String, Object> payload = Map.of(
                    "touser", message.touser(),
                    "template_id", message.templateId(),
                    "page", message.page(),
                    "data", message.data(),
                    "miniprogram_state", message.miniprogramState(),
                    "lang", message.lang());
            request.setEntity(new StringEntity(objectMapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));
            return request;
        } catch (java.net.URISyntaxException | JsonProcessingException error) {
            throw new WechatException("微信订阅消息请求构造失败", null, error);
        }
    }

    private JsonNode execute(org.apache.hc.client5.http.classic.methods.HttpUriRequestBase request) {
        try (CloseableHttpResponse response = http.execute(request)) {
            if (response.getCode() < 200 || response.getCode() >= 300 || response.getEntity() == null) {
                throw new WechatException("微信服务暂时不可用", null);
            }
            byte[] body = readLimited(response.getEntity().getContent());
            return objectMapper.readTree(body);
        } catch (WechatException error) {
            throw error;
        } catch (IOException error) {
            throw new WechatException("微信服务暂时不可用", null, error);
        }
    }

    private void invalidateToken(String rejectedToken) {
        redis.execute(DELETE_IF_VALUE_SCRIPT, List.of(tokenKey), rejectedToken);
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[4096];
        int total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) {
                throw new WechatException("微信响应过大", null);
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String join(CompletableFuture<String> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw error;
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @PreDestroy
    void close() throws IOException {
        http.close();
    }
}

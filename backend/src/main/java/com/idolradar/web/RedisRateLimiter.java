package com.idolradar.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import com.idolradar.api.AppException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 基于 Redis 的固定窗口限流器，作为所有 API 实例的共享准入门。 */
@Component
public class RedisRateLimiter implements DistributedRateLimiter {
    // INCR 与首个窗口过期设置必须在一次 Lua 操作中完成；分开执行可能留下永不过期的计数器。
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]); "
                    + "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return current;",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean allow(String scope, String subject, int limit, Duration window) {
        // 对主体做哈希，避免 Redis key 和运维工具暴露 openId 或客户端 IP。
        String key = "idolradar:rate:" + scope + ":" + sha256(subject);
        try {
            Long count = redis.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    Long.toString(window.toMillis()));
            if (count == null) {
                // 限流属于安全边界，Redis 结果不确定时默认拒绝。
                throw unavailable(null);
            }
            return count <= limit;
        } catch (DataAccessException error) {
            // 多实例部署中 Redis 故障时不得静默放行。
            throw unavailable(error);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static AppException unavailable(Throwable cause) {
        return new AppException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RATE_LIMIT_UNAVAILABLE",
                "服务暂时不可用",
                cause);
    }
}

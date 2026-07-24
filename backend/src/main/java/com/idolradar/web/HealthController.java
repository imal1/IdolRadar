package com.idolradar.web;

import java.util.Map;

import com.idolradar.api.ApiResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 区分进程存活状态与承载 API 流量所需依赖的就绪状态。 */
@RestController
public class HealthController {
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;

    public HealthController(JdbcClient jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @GetMapping("/healthz")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "ok"));
    }

    /** 仅当持久化存储与分布式限流都可访问时报告就绪。 */
    @GetMapping("/readyz")
    public ResponseEntity<ApiResponse<Map<String, String>>> readiness() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new IllegalStateException("Redis ping returned no response");
            }
            return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "ready")));
        } catch (DataAccessException | IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("DEPENDENCY_UNAVAILABLE", "依赖服务暂时不可用"));
        }
    }
}

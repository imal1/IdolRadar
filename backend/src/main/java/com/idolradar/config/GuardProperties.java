package com.idolradar.config;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 守护数量上限策略：一个用户能同时守护多少位 idol 由服务端配置决定，而不是由客户端硬编码。
 *
 * <p>客户端类型取自会话（登录时写入 {@code idr_user_session.client_type}），不取自请求头等
 * 客户端可控输入，见 ADR-0002。未在下表出现的客户端一律回落到 {@code default-limit}。
 * 调整已有客户端的上限只需改环境变量 {@code IDOLRADAR_GUARD_LIMIT_<CLIENT>}，不需要改代码。
 */
@ConfigurationProperties("idolradar.guard")
public record GuardProperties(Integer defaultLimit, Map<String, Integer> limits) {

    public GuardProperties {
        defaultLimit = defaultLimit == null ? 1 : defaultLimit;
        // 配置键统一规整成小写，避免 application.yml 里写成 iOS 时永远查不到。
        limits = limits == null ? Map.of() : limits.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> entry.getKey().trim().toLowerCase(Locale.ROOT), Map.Entry::getValue));
        // 上限必须为正：0 或负数会让所有守护请求被拒绝，那是配置写错，不是一种策略。
        requirePositive(defaultLimit, "idolradar.guard.default-limit");
        limits.forEach((client, limit) -> requirePositive(limit, "idolradar.guard.limits." + client));
    }

    /** 取该客户端的守护上限；客户端标识大小写不敏感，未知客户端回落到默认上限。 */
    public int limitFor(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return defaultLimit;
        }
        Integer limit = limits.get(clientId.trim().toLowerCase(Locale.ROOT));
        return limit == null ? defaultLimit : limit;
    }

    private static void requirePositive(Integer limit, String property) {
        if (limit == null || limit < 1) {
            throw new IllegalArgumentException(property + " must be at least 1");
        }
    }
}

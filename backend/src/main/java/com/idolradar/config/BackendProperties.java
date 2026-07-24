package com.idolradar.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** API 与通知链路共享的业务配置，并在绑定阶段收紧可接受范围。 */
@ConfigurationProperties("idolradar")
public record BackendProperties(
        Duration sessionTtl,
        String subscribeTemplateId) {

    public BackendProperties {
        sessionTtl = sessionTtl == null ? Duration.ofDays(30) : sessionTtl;
        subscribeTemplateId = subscribeTemplateId == null ? "" : subscribeTemplateId.trim();
        if (sessionTtl.compareTo(Duration.ofSeconds(1)) < 0
                || sessionTtl.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("idolradar.session-ttl must be between 1 second and 365 days");
        }
    }
}

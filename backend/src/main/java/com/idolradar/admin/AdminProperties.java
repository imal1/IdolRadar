package com.idolradar.admin;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 管理员会话配置；与小程序用户会话配置完全分离。 */
@ConfigurationProperties("idolradar.admin")
public record AdminProperties(Duration sessionTtl) {
    public AdminProperties {
        sessionTtl = sessionTtl == null ? Duration.ofHours(12) : sessionTtl;
        if (sessionTtl.compareTo(Duration.ofMinutes(5)) < 0
                || sessionTtl.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException(
                    "idolradar.admin.session-ttl must be between 5 minutes and 30 days");
        }
    }
}

package com.idolradar.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 经验证用户与不透明 token 会话的持久化边界。 */
public interface AuthRepository {
    /** WeChat 验证 openId 后，幂等创建对应用户。 */
    UUID ensureUser(String openId);

    /** 只持久化 token 哈希，并限制每位用户的有效会话数。 */
    void createSession(UUID userId, String tokenHash, Instant expiresAt);

    /** 通过 token 哈希解析未过期会话。 */
    Optional<StoredIdentity> findSession(String tokenHash);

    record StoredIdentity(UUID userId, String openId, Instant expiresAt) {
    }
}

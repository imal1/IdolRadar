package com.idolradar.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 经验证用户与不透明 token 会话的持久化边界。 */
public interface AuthRepository {
    /** WeChat 验证 openId 后，幂等创建对应用户。 */
    UUID ensureUser(String openId);

    /**
     * 只持久化 token 哈希，并限制每位用户的有效会话数。
     *
     * <p>{@code clientType} 由登录端点按其登录方式写死，不接受客户端传入：请求侧只读不写，
     * 用户无法通过伪造请求给自己换一个上限更高的客户端类型。
     */
    void createSession(UUID userId, String tokenHash, Instant expiresAt, String clientType);

    /** 通过 token 哈希解析未过期会话。 */
    Optional<StoredIdentity> findSession(String tokenHash);

    record StoredIdentity(UUID userId, String openId, Instant expiresAt, String clientType) {
    }
}

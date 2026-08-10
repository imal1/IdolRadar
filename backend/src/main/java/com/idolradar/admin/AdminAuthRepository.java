package com.idolradar.admin;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 管理员账号与会话的持久化接口；不得读写微信用户会话表。 */
public interface AdminAuthRepository {
    Optional<Credential> findCredential(String username);

    /** 在确认账号仍启用后创建会话；账号已停用时返回 false。 */
    boolean createSession(UUID adminId, String tokenHash, Instant expiresAt);

    Optional<StoredIdentity> findSession(String tokenHash);

    void revokeSession(String tokenHash);

    /** 停用账号并原子吊销全部会话；账号不存在时返回 false。 */
    boolean revokeAccess(UUID adminId);

    UUID createAdmin(String username, String passwordHash);

    record Credential(UUID adminId, String username, String passwordHash, boolean enabled) {
    }

    record StoredIdentity(UUID adminId, String username, Instant expiresAt) {
    }
}

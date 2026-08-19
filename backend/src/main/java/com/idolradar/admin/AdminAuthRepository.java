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

    /**
     * 幂等创建管理员：用户名已存在时不创建、不覆盖口令，只返回既有账号。
     * bootstrap 会被运维重复执行，覆盖语义会让一次误操作直接换掉线上凭据。
     */
    CreatedAdmin createAdmin(String username, String passwordHash);

    /** created 为 false 表示该用户名此前已存在，本次未写入。 */
    record CreatedAdmin(UUID adminId, boolean created) {
    }

    record Credential(UUID adminId, String username, String passwordHash, boolean enabled) {
    }

    record StoredIdentity(UUID adminId, String username, Instant expiresAt) {
    }
}

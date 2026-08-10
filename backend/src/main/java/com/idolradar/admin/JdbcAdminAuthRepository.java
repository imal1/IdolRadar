package com.idolradar.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL 管理员认证仓库；账号停用与会话吊销在同一事务完成。 */
@Repository
public class JdbcAdminAuthRepository implements AdminAuthRepository {
    private static final int MAX_SESSIONS_PER_ADMIN = 5;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcAdminAuthRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<Credential> findCredential(String username) {
        return jdbc.sql("SELECT id, username, password_hash, enabled "
                        + "FROM idr_admin_account WHERE username = :username")
                .param("username", username)
                .query(this::mapCredential)
                .optional();
    }

    @Override
    public boolean createSession(UUID adminId, String tokenHash, Instant expiresAt) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            // 与停用操作争用同一账号行锁，防止“验密后、建会话前”被停用的账号重新登录。
            Optional<Boolean> enabled = jdbc.sql(
                            "SELECT enabled FROM idr_admin_account WHERE id = :adminId FOR UPDATE")
                    .param("adminId", adminId)
                    .query(Boolean.class)
                    .optional();
            if (enabled.isEmpty() || !enabled.get()) {
                return false;
            }
            jdbc.sql("INSERT INTO idr_admin_session (token_hash, admin_id, expires_at) "
                            + "VALUES (:tokenHash, :adminId, :expiresAt)")
                    .param("tokenHash", tokenHash)
                    .param("adminId", adminId)
                    .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                    .update();
            jdbc.sql("UPDATE idr_admin_account SET last_login_at = NOW(), updated_at = NOW() "
                            + "WHERE id = :adminId")
                    .param("adminId", adminId)
                    .update();
            // 锁内裁剪，确保并发登录也只能保留固定数量的有效管理员会话。
            jdbc.sql("DELETE FROM idr_admin_session WHERE admin_id = :adminId AND ("
                            + "expires_at <= NOW() OR revoked_at IS NOT NULL OR token_hash NOT IN ("
                            + "SELECT token_hash FROM idr_admin_session WHERE admin_id = :adminId "
                            + "AND expires_at > NOW() AND revoked_at IS NULL "
                            + "ORDER BY created_at DESC, token_hash DESC LIMIT :limit))")
                    .param("adminId", adminId)
                    .param("limit", MAX_SESSIONS_PER_ADMIN)
                    .update();
            return true;
        }));
    }

    @Override
    public Optional<StoredIdentity> findSession(String tokenHash) {
        Optional<SessionRow> session = jdbc.sql(
                        "SELECT s.admin_id, a.username, s.expires_at, s.last_used_at "
                                + "FROM idr_admin_session s "
                                + "JOIN idr_admin_account a ON a.id = s.admin_id "
                                + "WHERE s.token_hash = :tokenHash AND s.expires_at > NOW() "
                                + "AND s.revoked_at IS NULL AND a.enabled = true")
                .param("tokenHash", tokenHash)
                .query(this::mapSession)
                .optional();
        if (session.isEmpty()) {
            return Optional.empty();
        }
        SessionRow row = session.get();
        // 限制活跃时间写频率，避免管理页面轮询导致每次请求都更新数据库。
        if (row.lastUsedAt().isBefore(Instant.now().minusSeconds(300))) {
            jdbc.sql("UPDATE idr_admin_session SET last_used_at = NOW() "
                            + "WHERE token_hash = :tokenHash "
                            + "AND revoked_at IS NULL AND last_used_at < NOW() - INTERVAL '5 minutes'")
                    .param("tokenHash", tokenHash)
                    .update();
        }
        return Optional.of(new StoredIdentity(row.adminId(), row.username(), row.expiresAt()));
    }

    @Override
    public void revokeSession(String tokenHash) {
        jdbc.sql("UPDATE idr_admin_session SET revoked_at = COALESCE(revoked_at, NOW()) "
                        + "WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .update();
    }

    @Override
    public boolean revokeAccess(UUID adminId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Optional<UUID> locked = jdbc.sql(
                            "SELECT id FROM idr_admin_account WHERE id = :adminId FOR UPDATE")
                    .param("adminId", adminId)
                    .query(UUID.class)
                    .optional();
            if (locked.isEmpty()) {
                return false;
            }
            jdbc.sql("UPDATE idr_admin_account SET enabled = false, version = version + 1, "
                            + "updated_at = NOW() WHERE id = :adminId")
                    .param("adminId", adminId)
                    .update();
            jdbc.sql("UPDATE idr_admin_session SET revoked_at = COALESCE(revoked_at, NOW()) "
                            + "WHERE admin_id = :adminId")
                    .param("adminId", adminId)
                    .update();
            return true;
        }));
    }

    @Override
    public UUID createAdmin(String username, String passwordHash) {
        return jdbc.sql("INSERT INTO idr_admin_account (username, password_hash) "
                        + "VALUES (:username, :passwordHash) RETURNING id")
                .param("username", username)
                .param("passwordHash", passwordHash)
                .query(UUID.class)
                .single();
    }

    private Credential mapCredential(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Credential(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                resultSet.getBoolean("enabled"));
    }

    private SessionRow mapSession(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SessionRow(
                resultSet.getObject("admin_id", UUID.class),
                resultSet.getString("username"),
                resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("last_used_at", OffsetDateTime.class).toInstant());
    }

    private record SessionRow(UUID adminId, String username, Instant expiresAt, Instant lastUsedAt) {
    }
}

package com.idolradar.auth;

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

/** PostgreSQL 会话仓库，支持并发安全的用户建档和会话裁剪。 */
@Repository
public class JdbcAuthRepository implements AuthRepository {
    private static final int MAX_SESSIONS_PER_USER = 5;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcAuthRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public UUID ensureUser(String openId) {
        // 同一 openId 的并发登录汇聚到同一行，避免先查后插竞态。
        jdbc.sql("INSERT INTO users (openid) VALUES (:openId) ON CONFLICT (openid) DO NOTHING")
                .param("openId", openId)
                .update();
        return jdbc.sql("SELECT id FROM users WHERE openid = :openId")
                .param("openId", openId)
                .query(UUID.class)
                .single();
    }

    @Override
    public void createSession(UUID userId, String tokenHash, Instant expiresAt) {
        transactions.executeWithoutResult(status -> {
            // 按用户串行创建会话，避免并发登录同时绕过数量上限。
            jdbc.sql("SELECT id FROM users WHERE id = :userId FOR UPDATE")
                    .param("userId", userId)
                    .query(UUID.class)
                    .single();
            jdbc.sql("INSERT INTO sessions (token_hash, user_id, expires_at) "
                            + "VALUES (:tokenHash, :userId, :expiresAt)")
                    .param("tokenHash", tokenHash)
                    .param("userId", userId)
                    .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                    .update();
            // 在持锁事务内裁剪会话，确定性保留最新的有效会话。
            jdbc.sql("DELETE FROM sessions WHERE user_id = :userId AND (expires_at <= NOW() OR "
                            + "token_hash NOT IN ("
                            + "SELECT token_hash FROM sessions WHERE user_id = :userId "
                            + "AND expires_at > NOW() ORDER BY created_at DESC, token_hash DESC LIMIT :limit))")
                    .param("userId", userId)
                    .param("limit", MAX_SESSIONS_PER_USER)
                    .update();
        });
    }

    @Override
    public Optional<StoredIdentity> findSession(String tokenHash) {
        Optional<SessionRow> session = jdbc.sql(
                        "SELECT s.user_id, s.expires_at, s.last_used_at, u.openid "
                                + "FROM sessions s JOIN users u ON u.id = s.user_id "
                                + "WHERE s.token_hash = :tokenHash AND s.expires_at > NOW()")
                .param("tokenHash", tokenHash)
                .query(this::mapSession)
                .optional();
        if (session.isEmpty()) {
            return Optional.empty();
        }
        SessionRow row = session.get();
        // 每五分钟至多更新一次，在保留活跃度数据的同时避免每个 API 请求都写库。
        if (row.lastUsedAt().isBefore(Instant.now().minusSeconds(300))) {
            jdbc.sql("UPDATE sessions SET last_used_at = NOW() WHERE token_hash = :tokenHash "
                            + "AND last_used_at < NOW() - INTERVAL '5 minutes'")
                    .param("tokenHash", tokenHash)
                    .update();
        }
        return Optional.of(new StoredIdentity(row.userId(), row.openId(), row.expiresAt()));
    }

    private SessionRow mapSession(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SessionRow(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("openid"),
                resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("last_used_at", OffsetDateTime.class).toInstant());
    }

    private record SessionRow(UUID userId, String openId, Instant expiresAt, Instant lastUsedAt) {
    }
}

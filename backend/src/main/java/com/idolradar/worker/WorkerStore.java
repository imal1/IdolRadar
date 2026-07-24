package com.idolradar.worker;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link FeedRepository} 与 {@link NotificationRepository} 的 JDBC 实现。
 *
 * <p>TransactionTemplate 与行锁保证 post+outbox、delivery+额度及重试状态原子更新。
 * outbox 使用 SKIP LOCKED 与 lease claim；idol/post 条件更新阻止旧任务完成或重试
 * 已被新 post 替换的 outbox。
 */
@Repository
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class WorkerStore implements FeedRepository, NotificationRepository {
    private static final int MAX_SUBSCRIBE_QUOTA = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public WorkerStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<WorkerModels.Source> loadEnabledSources() {
        return jdbc.query(
                "SELECT id, idol_id, rss_url, channel FROM sources WHERE enabled = TRUE ORDER BY id ASC",
                (result, row) -> new WorkerModels.Source(
                        result.getString("id"),
                        result.getString("idol_id"),
                        result.getString("rss_url"),
                        result.getString("channel")));
    }

    @Override
    public void updateSourceStatus(String sourceId, WorkerModels.SourceStatus status) {
        jdbc.update("""
                UPDATE sources
                SET last_fetch_at = NOW(),
                    last_fetch_status = ?,
                    last_fetch_error_code = ?,
                    last_fetch_item_count = ?,
                    last_fetch_new_count = ?,
                    updated_at = NOW()
                WHERE id = ?
                """,
                status.status(),
                status.errorCode(),
                status.itemCount(),
                status.newCount(),
                sourceId);
    }

    /** 同一事务插入 post，并将该 idol 最新 post 写入 outbox。 */
    @Override
    public Optional<WorkerModels.Post> insertPostAndEnqueue(WorkerModels.Post post) {
        Optional<WorkerModels.Post> result = transactions.execute(status -> {
            List<WorkerModels.Post> inserted = jdbc.query("""
                    INSERT INTO posts
                        (id, idol_id, source_id, channel, title, summary, link, published_at, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING id, idol_id, source_id, channel, title, summary, link, published_at, fetched_at
                    """,
                    (rows, row) -> new WorkerModels.Post(
                            rows.getString("id"),
                            rows.getString("idol_id"),
                            rows.getString("source_id"),
                            rows.getString("channel"),
                            rows.getString("title"),
                            rows.getString("summary"),
                            rows.getString("link"),
                            rows.getTimestamp("published_at").toInstant(),
                            rows.getTimestamp("fetched_at").toInstant()),
                    post.id(),
                    post.idolId(),
                    post.sourceId(),
                    post.channel(),
                    post.title(),
                    post.summary(),
                    post.link(),
                    Timestamp.from(post.publishedAt()),
                    Timestamp.from(post.fetchedAt()));
            Optional<WorkerModels.Post> created = inserted.stream().findFirst();
            created.ifPresent(this::enqueueLatestPost);
            return created;
        });
        return result == null ? Optional.empty() : result;
    }

    /** 仅当候选 post 的 {@code (published_at, id)} 更大时替换该 idol 的 outbox。 */
    private void enqueueLatestPost(WorkerModels.Post post) {
        jdbc.update("""
                INSERT INTO notification_outbox
                    (idol_id, post_id, status, attempt_count, next_attempt_at,
                     lease_expires_at, error_code, completed_at)
                VALUES (?, ?, 'pending', 0, NOW(), NULL, NULL, NULL)
                ON CONFLICT (idol_id) DO UPDATE
                SET post_id = EXCLUDED.post_id,
                    status = 'pending',
                    attempt_count = 0,
                    next_attempt_at = NOW(),
                    lease_expires_at = NULL,
                    error_code = NULL,
                    completed_at = NULL,
                    updated_at = NOW()
                WHERE (
                  SELECT candidate.published_at > queued.published_at
                      OR (candidate.published_at = queued.published_at AND candidate.id > queued.id)
                  FROM posts candidate, posts queued
                  WHERE candidate.id = EXCLUDED.post_id
                    AND queued.id = notification_outbox.post_id
                )
                """, post.idolId(), post.id());
    }

    @Override
    public Optional<WorkerModels.PostWithIdol> loadPostWithIdol(String postId) {
        return jdbc.query("""
                SELECT p.id, p.idol_id, p.title, p.published_at, i.name AS idol_name
                FROM posts p
                JOIN idols i ON i.id = p.idol_id
                WHERE p.id = ?
                """,
                (result, row) -> new WorkerModels.PostWithIdol(
                        result.getString("id"),
                        result.getString("idol_id"),
                        result.getString("idol_name"),
                        result.getString("title"),
                        result.getTimestamp("published_at").toInstant()),
                postId).stream().findFirst();
    }

    @Override
    public List<WorkerModels.UserTarget> loadEligibleUsers(
            String postId,
            String idolId,
            String templateId,
            UUID afterId,
            int limit) {
        if (afterId == null) {
            return jdbc.query("""
                    SELECT u.id, u.openid
                    FROM users u
                    LEFT JOIN notification_deliveries d
                      ON d.post_id = ? AND d.user_id = u.id
                    WHERE u.idol_id = ?
                      AND u.subscribe_template_id = ?
                      AND u.subscribe_quota > 0
                      AND d.user_id IS NULL
                    ORDER BY u.id ASC
                    LIMIT ?
                    """,
                    WorkerStore::userTarget,
                    postId, idolId, templateId, limit);
        }
        return jdbc.query("""
                SELECT u.id, u.openid
                FROM users u
                LEFT JOIN notification_deliveries d
                  ON d.post_id = ? AND d.user_id = u.id
                WHERE u.idol_id = ?
                  AND u.subscribe_template_id = ?
                  AND u.subscribe_quota > 0
                  AND d.user_id IS NULL
                  AND u.id > ?
                ORDER BY u.id ASC
                LIMIT ?
                """,
                WorkerStore::userTarget,
                postId, idolId, templateId, afterId, limit);
    }

    /** 同一事务创建幂等 delivery，并扣减匹配用户、idol、模板的一次额度。 */
    @Override
    public boolean claimDelivery(String postId, UUID userId, String idolId, String templateId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO notification_deliveries
                        (post_id, user_id, template_id, status, attempt_count, quota_reserved)
                    VALUES (?, ?, ?, 'reserved', 1, TRUE)
                    ON CONFLICT DO NOTHING
                    """, postId, userId, templateId);
            if (inserted != 1) {
                status.setRollbackOnly();
                return false;
            }
            int reserved = jdbc.update("""
                    UPDATE users
                    SET subscribe_quota = subscribe_quota - 1, updated_at = NOW()
                    WHERE id = ? AND idol_id = ? AND subscribe_template_id = ?
                      AND subscribe_quota > 0
                    """, userId, idolId, templateId);
            if (reserved != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    /** 在 HTTP POST 前落库 sending，建立不可安全重试边界。 */
    @Override
    public void markDeliverySending(String postId, UUID userId) {
        int updated = jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'sending', attempted_at = NOW(), updated_at = NOW()
                WHERE post_id = ? AND user_id = ? AND status = 'reserved'
                """, postId, userId);
        if (updated != 1) throw new IllegalStateException("Delivery reservation is not active");
    }

    /** reserved 尚未越过 POST 边界，可保留额度重试；耗尽后退还额度。 */
    @Override
    public Optional<WorkerModels.RetrySchedule> scheduleReservedRetry(
            String postId,
            UUID userId,
            String errorCode,
            int maxAttempts,
            Duration baseDelay) {
        return transactions.execute(status -> {
            Optional<DeliveryState> state = lockDelivery(postId, userId, "reserved");
            if (state.isEmpty()) return Optional.empty();
            DeliveryState delivery = state.get();
            boolean exhausted = delivery.attemptCount() >= maxAttempts;
            if (exhausted && delivery.quotaReserved()) {
                refundQuota(userId, delivery.templateId());
            }
            jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = ?, error_code = ?, quota_reserved = ?,
                        next_attempt_at = ?, finished_at = ?, updated_at = NOW()
                    WHERE post_id = ? AND user_id = ?
                    """,
                    exhausted ? "failed" : "retryable",
                    exhausted ? "RETRY_EXHAUSTED" : errorCode,
                    exhausted ? false : delivery.quotaReserved(),
                    exhausted ? null : Timestamp.from(Instant.now().plus(retryDelay(delivery.attemptCount(), baseDelay))),
                    exhausted ? Timestamp.from(Instant.now()) : null,
                    postId,
                    userId);
            return Optional.of(new WorkerModels.RetrySchedule(!exhausted));
        });
    }

    /** 明确的 WeChat 全局错误可退还额度后重试；未知结果不进入此路径。 */
    @Override
    public WorkerModels.RetrySchedule scheduleAttemptedRetry(
            String postId,
            UUID userId,
            String errorCode,
            int maxAttempts,
            Duration baseDelay) {
        WorkerModels.RetrySchedule result = transactions.execute(status -> {
            DeliveryState delivery = lockDelivery(postId, userId, "sending")
                    .orElseThrow(() -> new IllegalStateException("Active delivery attempt was not found"));
            if (delivery.quotaReserved()) refundQuota(userId, delivery.templateId());
            boolean exhausted = delivery.attemptCount() >= maxAttempts;
            jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = ?, error_code = ?, quota_reserved = FALSE,
                        next_attempt_at = ?, finished_at = ?, updated_at = NOW()
                    WHERE post_id = ? AND user_id = ?
                    """,
                    exhausted ? "failed" : "retryable",
                    exhausted ? "RETRY_EXHAUSTED" : errorCode,
                    exhausted ? null : Timestamp.from(Instant.now().plus(retryDelay(delivery.attemptCount(), baseDelay))),
                    exhausted ? Timestamp.from(Instant.now()) : null,
                    postId,
                    userId);
            return new WorkerModels.RetrySchedule(!exhausted);
        });
        if (result == null) throw new IllegalStateException("Delivery retry transaction failed");
        return result;
    }

    @Override
    public void failDeliveryAndClearQuota(String postId, UUID userId, String templateId, String errorCode) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = 'failed', error_code = ?, quota_reserved = FALSE,
                        finished_at = NOW(), updated_at = NOW()
                    WHERE post_id = ? AND user_id = ? AND status = 'sending'
                    """, errorCode, postId, userId);
            jdbc.update("""
                    UPDATE users
                    SET subscribe_quota = 0, updated_at = NOW()
                    WHERE id = ? AND subscribe_template_id = ?
                    """, userId, templateId);
        });
    }

    @Override
    public void finishDelivery(String postId, UUID userId, String deliveryStatus, String errorCode) {
        int updated = jdbc.update("""
                UPDATE notification_deliveries
                SET status = ?, error_code = ?, quota_reserved = FALSE,
                    finished_at = NOW(), updated_at = NOW()
                WHERE post_id = ? AND user_id = ? AND status = 'sending'
                """, deliveryStatus, errorCode, postId, userId);
        if (updated != 1) throw new IllegalStateException("Delivery attempt is not active");
    }

    @Override
    public List<WorkerModels.RetryDelivery> loadDueDeliveries(int limit) {
        return jdbc.query("""
                SELECT p.id, p.idol_id, p.title, p.published_at, i.name AS idol_name,
                       u.id AS user_id, u.openid
                FROM notification_deliveries d
                JOIN posts p ON p.id = d.post_id
                JOIN idols i ON i.id = p.idol_id
                JOIN users u ON u.id = d.user_id
                WHERE d.status = 'retryable' AND d.next_attempt_at <= NOW()
                ORDER BY d.next_attempt_at ASC, d.post_id ASC, d.user_id ASC
                LIMIT ?
                """,
                (result, row) -> new WorkerModels.RetryDelivery(
                        new WorkerModels.PostWithIdol(
                                result.getString("id"),
                                result.getString("idol_id"),
                                result.getString("idol_name"),
                                result.getString("title"),
                                result.getTimestamp("published_at").toInstant()),
                        result.getObject("user_id", UUID.class),
                        result.getString("openid")),
                limit);
    }

    /**
     * 先锁 delivery、再锁用户，串行化同一投递的重试竞争。
     * 订阅变化或次数耗尽时退还已预留额度；额度不足时终止，成功领取才递增尝试次数。
     */
    @Override
    public boolean claimRetryDelivery(
            WorkerModels.RetryDelivery candidate,
            String templateId,
            int maxAttempts) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            String postId = candidate.post().id();
            UUID userId = candidate.userId();
            Optional<DeliveryState> state = lockDueDelivery(postId, userId);
            if (state.isEmpty()) return false;
            DeliveryState delivery = state.get();
            Optional<UserSubscription> subscription = jdbc.query("""
                    SELECT idol_id, subscribe_template_id
                    FROM users WHERE id = ? FOR UPDATE
                    """,
                    (result, row) -> new UserSubscription(
                            result.getString("idol_id"),
                            result.getString("subscribe_template_id")),
                    userId).stream().findFirst();
            boolean compatible = subscription.isPresent()
                    && candidate.post().idolId().equals(subscription.get().idolId())
                    && templateId.equals(subscription.get().templateId())
                    && templateId.equals(delivery.templateId());
            boolean exhausted = delivery.attemptCount() >= maxAttempts;
            if (!compatible || exhausted) {
                if (delivery.quotaReserved()) refundQuota(userId, delivery.templateId());
                jdbc.update("""
                        UPDATE notification_deliveries
                        SET status = 'failed', error_code = ?, quota_reserved = FALSE,
                            next_attempt_at = NULL, finished_at = NOW(), updated_at = NOW()
                        WHERE post_id = ? AND user_id = ?
                        """, exhausted ? "RETRY_EXHAUSTED" : "SUBSCRIPTION_CHANGED", postId, userId);
                return false;
            }
            if (!delivery.quotaReserved()) {
                int quota = jdbc.update("""
                        UPDATE users
                        SET subscribe_quota = subscribe_quota - 1, updated_at = NOW()
                        WHERE id = ? AND subscribe_quota > 0
                        """, userId);
                if (quota != 1) {
                    jdbc.update("""
                            UPDATE notification_deliveries
                            SET status = 'failed', error_code = 'QUOTA_UNAVAILABLE',
                                next_attempt_at = NULL, finished_at = NOW(), updated_at = NOW()
                            WHERE post_id = ? AND user_id = ?
                            """, postId, userId);
                    return false;
                }
            }
            jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = 'reserved', attempt_count = attempt_count + 1,
                        quota_reserved = TRUE, next_attempt_at = NULL,
                        finished_at = NULL, updated_at = NOW()
                    WHERE post_id = ? AND user_id = ?
                    """, postId, userId);
            return true;
        }));
    }

    /** 超时 reserved 转 retryable；超时 sending 转 uncertain，避免重复发送。 */
    @Override
    public WorkerModels.Reconciliation reconcileStaleDeliveries(Duration maxAge) {
        long milliseconds = maxAge.toMillis();
        return transactions.execute(status -> {
            int retrying = jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = 'retryable', error_code = 'WORKER_INTERRUPTED_BEFORE_SEND',
                        next_attempt_at = NOW(), updated_at = NOW()
                    WHERE status = 'reserved'
                      AND updated_at < NOW() - (?::bigint * INTERVAL '1 millisecond')
                    """, milliseconds);
            int uncertain = jdbc.update("""
                    UPDATE notification_deliveries
                    SET status = 'uncertain', error_code = 'WORKER_INTERRUPTED',
                        quota_reserved = FALSE, finished_at = NOW(), updated_at = NOW()
                    WHERE status = 'sending'
                      AND updated_at < NOW() - (?::bigint * INTERVAL '1 millisecond')
                    """, milliseconds);
            return new WorkerModels.Reconciliation(retrying, uncertain);
        });
    }

    /** 将 lease 过期的 processing outbox 恢复为 retryable。 */
    @Override
    public int recoverStaleOutbox() {
        return jdbc.update("""
                UPDATE notification_outbox
                SET status = 'retryable',
                    next_attempt_at = NOW(),
                    lease_expires_at = NULL,
                    error_code = 'WORKER_INTERRUPTED',
                    updated_at = NOW()
                WHERE status = 'processing'
                  AND lease_expires_at <= NOW()
                """);
    }

    /** 通过 FOR UPDATE SKIP LOCKED 原子 claim 一条到期 outbox 并设置 lease。 */
    @Override
    public Optional<WorkerModels.OutboxTask> claimNextOutbox(Duration lease) {
        List<WorkerModels.OutboxTask> claimed = jdbc.query("""
                WITH candidate AS (
                  SELECT idol_id
                  FROM notification_outbox
                  WHERE status IN ('pending', 'retryable')
                    AND next_attempt_at <= NOW()
                  ORDER BY next_attempt_at ASC, idol_id ASC
                  FOR UPDATE SKIP LOCKED
                  LIMIT 1
                )
                UPDATE notification_outbox outbox
                SET status = 'processing',
                    attempt_count = LEAST(outbox.attempt_count + 1, 100),
                    lease_expires_at = NOW() + (?::bigint * INTERVAL '1 millisecond'),
                    error_code = NULL,
                    updated_at = NOW()
                FROM candidate
                WHERE outbox.idol_id = candidate.idol_id
                RETURNING outbox.idol_id, outbox.post_id, outbox.attempt_count
                """,
                (rows, row) -> new WorkerModels.OutboxTask(
                        rows.getString("idol_id"),
                        rows.getString("post_id"),
                        rows.getInt("attempt_count")),
                lease.toMillis());
        return claimed.stream().findFirst();
    }

    /** 仅完成匹配 idol/post 的 processing 行，避免旧 worker 覆盖新 post。 */
    @Override
    public boolean completeOutbox(WorkerModels.OutboxTask task) {
        return jdbc.update("""
                UPDATE notification_outbox
                SET status = 'completed',
                    lease_expires_at = NULL,
                    error_code = NULL,
                    completed_at = NOW(),
                    updated_at = NOW()
                WHERE idol_id = ? AND post_id = ? AND status = 'processing'
                """, task.idolId(), task.postId()) == 1;
    }

    /** 仅重试匹配 idol/post 的 processing 行，避免旧 worker 覆盖新 post。 */
    @Override
    public void retryOutbox(
            WorkerModels.OutboxTask task,
            String errorCode,
            int maxAttempts,
            Duration baseDelay) {
        int attempt = Math.min(task.attemptCount(), maxAttempts);
        jdbc.update("""
                UPDATE notification_outbox
                SET status = 'retryable',
                    next_attempt_at = ?,
                    lease_expires_at = NULL,
                    error_code = ?,
                    updated_at = NOW()
                WHERE idol_id = ? AND post_id = ? AND status = 'processing'
                """,
                Timestamp.from(Instant.now().plus(retryDelay(attempt, baseDelay))),
                errorCode,
                task.idolId(),
                task.postId());
    }

    private Optional<DeliveryState> lockDelivery(String postId, UUID userId, String expectedStatus) {
        return jdbc.query("""
                SELECT attempt_count, quota_reserved, template_id
                FROM notification_deliveries
                WHERE post_id = ? AND user_id = ? AND status = ?
                FOR UPDATE
                """,
                (result, row) -> new DeliveryState(
                        result.getInt("attempt_count"),
                        result.getBoolean("quota_reserved"),
                        result.getString("template_id")),
                postId, userId, expectedStatus).stream().findFirst();
    }

    private Optional<DeliveryState> lockDueDelivery(String postId, UUID userId) {
        return jdbc.query("""
                SELECT attempt_count, quota_reserved, template_id
                FROM notification_deliveries
                WHERE post_id = ? AND user_id = ? AND status = 'retryable'
                  AND next_attempt_at <= NOW()
                FOR UPDATE
                """,
                (result, row) -> new DeliveryState(
                        result.getInt("attempt_count"),
                        result.getBoolean("quota_reserved"),
                        result.getString("template_id")),
                postId, userId).stream().findFirst();
    }

    private void refundQuota(UUID userId, String templateId) {
        jdbc.update("""
                UPDATE users
                SET subscribe_quota = LEAST(subscribe_quota + 1, ?), updated_at = NOW()
                WHERE id = ? AND subscribe_template_id = ?
                """, MAX_SUBSCRIBE_QUOTA, userId, templateId);
    }

    private static Duration retryDelay(int attemptCount, Duration baseDelay) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 20));
        long multiplier = 1L << exponent;
        long milliseconds;
        try {
            milliseconds = Math.multiplyExact(baseDelay.toMillis(), multiplier);
        } catch (ArithmeticException overflow) {
            milliseconds = Duration.ofHours(6).toMillis();
        }
        return Duration.ofMillis(Math.min(milliseconds, Duration.ofHours(6).toMillis()));
    }

    private static WorkerModels.UserTarget userTarget(java.sql.ResultSet result, int row)
            throws java.sql.SQLException {
        return new WorkerModels.UserTarget(
                result.getObject("id", UUID.class),
                result.getString("openid"));
    }

    private record DeliveryState(int attemptCount, boolean quotaReserved, String templateId) {}
    private record UserSubscription(String idolId, String templateId) {}
}

package com.idolradar.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.idolradar.api.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 推送投递看板的只读查询。
 *
 * <p>投递账本与 outbox 的状态早已完整落库，这里只负责把它们读出来：
 * 分布看整体、失败原因看根因、队列积压看是否卡住、明细看具体某一条。
 * 全部查询都不返回 openid 或 token——管理端排查推送不需要知道用户是谁。
 */
@Repository
public class AdminDeliveryStore {
    /** 投递账本的持久化状态，与 ck_idr_notification_delivery_status 一致。 */
    private static final Set<String> DELIVERY_STATUS = Set.of(
            "reserved", "sending", "retryable", "sent", "failed", "uncertain");
    /** 额外的伪筛选值：反复重试仍未送达的投递，运营最关心的一类。 */
    private static final String STUCK_FILTER = "stuck";
    /** 超过这个尝试次数仍未送达即视为卡住；worker 的正常重试通常一到两次内收敛。 */
    private static final int STUCK_ATTEMPTS = 3;
    private static final int MAX_PAGE = 200;
    private static final Duration MAX_RANGE = Duration.ofDays(30);
    private static final Duration DEFAULT_RANGE = Duration.ofDays(1);

    private final JdbcClient jdbc;

    public AdminDeliveryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按时间区间与 idol 查询投递明细、状态分布、失败原因与队列积压。
     *
     * @param rangeHours 回看窗口小时数；null 取默认 24 小时，上限 30 天，避免一次扫全表
     */
    public Map<String, Object> listDeliveries(String idolId, String status, Integer rangeHours) {
        OffsetDateTime since = OffsetDateTime.now().minus(range(rangeHours));
        String idol = blankToNull(idolId);
        String statusFilter = normalizeStatus(status);
        boolean stuckOnly = STUCK_FILTER.equals(statusFilter);

        List<Map<String, Object>> deliveries = jdbc.sql("""
                        SELECT d.post_id, d.user_id, d.status, d.error_code, d.attempt_count,
                               d.attempted_at, d.finished_at, d.next_attempt_at, d.created_at,
                               d.first_opened_at, d.open_count,
                               p.idol_id, p.title, i.name AS idol_name
                        FROM idr_notification_delivery d
                        JOIN idr_post p ON p.id = d.post_id
                        JOIN idr_idol i ON i.id = p.idol_id
                        WHERE d.created_at >= :since
                          AND (:idolId IS NULL OR p.idol_id = :idolId)
                          AND (:status IS NULL OR d.status = :status)
                          AND (:stuckOnly = FALSE
                               OR (d.attempt_count >= :stuckAttempts AND d.status <> 'sent'))
                        ORDER BY d.created_at DESC, d.post_id ASC
                        LIMIT :limit
                        """)
                .param("since", since)
                .param("idolId", idol)
                .param("status", stuckOnly ? null : statusFilter)
                .param("stuckOnly", stuckOnly)
                .param("stuckAttempts", STUCK_ATTEMPTS)
                .param("limit", MAX_PAGE)
                .query((resultSet, rowNumber) -> serializeDelivery(resultSet))
                .list();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deliveries", deliveries);
        result.put("summary", summary(since, idol));
        result.put("failures", failureReasons(since, idol));
        result.put("queue", queue(idol));
        return result;
    }

    /**
     * 状态分布与两个转化率。
     *
     * <p>成功率的分母只取已出结论的投递（sent/failed/uncertain）：把还在重试的算进去
     * 会让刚发起的一批推送看起来像是失败，运营会误判。
     */
    private Map<String, Object> summary(OffsetDateTime since, String idolId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String value : List.of("reserved", "sending", "retryable", "sent", "failed", "uncertain")) {
            counts.put(value, 0);
        }
        jdbc.sql("""
                        SELECT d.status, COUNT(*)::integer AS total
                        FROM idr_notification_delivery d
                        JOIN idr_post p ON p.id = d.post_id
                        WHERE d.created_at >= :since AND (:idolId IS NULL OR p.idol_id = :idolId)
                        GROUP BY d.status
                        """)
                .param("since", since)
                .param("idolId", idolId)
                .query((resultSet, rowNumber) -> counts.put(
                        resultSet.getString("status"), resultSet.getInt("total")))
                .list();

        Map<String, Object> extra = jdbc.sql("""
                        SELECT COUNT(*) FILTER (
                                 WHERE d.attempt_count >= :stuckAttempts AND d.status <> 'sent'
                               )::integer AS stuck,
                               COUNT(*) FILTER (
                                 WHERE d.status = 'sent' AND d.open_count > 0
                               )::integer AS opened
                        FROM idr_notification_delivery d
                        JOIN idr_post p ON p.id = d.post_id
                        WHERE d.created_at >= :since AND (:idolId IS NULL OR p.idol_id = :idolId)
                        """)
                .param("since", since)
                .param("idolId", idolId)
                .param("stuckAttempts", STUCK_ATTEMPTS)
                .query((resultSet, rowNumber) -> Map.<String, Object>of(
                        "stuck", resultSet.getInt("stuck"),
                        "opened", resultSet.getInt("opened")))
                .single();

        int sent = (int) counts.get("sent");
        int concluded = sent + (int) counts.get("failed") + (int) counts.get("uncertain");
        Map<String, Object> summary = new LinkedHashMap<>(counts);
        summary.put("total", counts.values().stream().mapToInt(value -> (int) value).sum());
        summary.put("stuck", extra.get("stuck"));
        summary.put("opened", extra.get("opened"));
        summary.put("successRate", ratio(sent, concluded));
        summary.put("openRate", ratio((int) extra.get("opened"), sent));
        return summary;
    }

    /** 失败原因分布：按错误码聚合，管理员据此判断是模板问题、额度问题还是上游问题。 */
    private List<Map<String, Object>> failureReasons(OffsetDateTime since, String idolId) {
        return jdbc.sql("""
                        SELECT COALESCE(d.error_code, 'UNKNOWN') AS error_code,
                               COUNT(*)::integer AS total
                        FROM idr_notification_delivery d
                        JOIN idr_post p ON p.id = d.post_id
                        WHERE d.created_at >= :since
                          AND (:idolId IS NULL OR p.idol_id = :idolId)
                          AND d.status IN ('failed', 'retryable', 'uncertain')
                        GROUP BY 1 ORDER BY total DESC, 1 ASC LIMIT :limit
                        """)
                .param("since", since)
                .param("idolId", idolId)
                .param("limit", 20)
                .query((resultSet, rowNumber) -> {
                    Map<String, Object> reason = new LinkedHashMap<>();
                    reason.put("errorCode", resultSet.getString("error_code"));
                    reason.put("total", resultSet.getInt("total"));
                    return reason;
                })
                .list();
    }

    /**
     * 待发队列积压。
     *
     * <p>不设时间窗口：积压本来就是「早该发出去却还在队列里」，按创建时间过滤会把最该看的那条藏起来。
     */
    private Map<String, Object> queue(String idolId) {
        Map<String, Object> queue = new LinkedHashMap<>();
        for (String value : List.of("pending", "processing", "retryable")) {
            queue.put(value, 0);
        }
        jdbc.sql("""
                        SELECT status, COUNT(*)::integer AS total
                        FROM idr_notification_outbox
                        WHERE status <> 'completed' AND (:idolId IS NULL OR idol_id = :idolId)
                        GROUP BY status
                        """)
                .param("idolId", idolId)
                .query((resultSet, rowNumber) -> queue.put(
                        resultSet.getString("status"), resultSet.getInt("total")))
                .list();

        OffsetDateTime oldest = jdbc.sql("""
                        SELECT MIN(created_at) AS oldest FROM idr_notification_outbox
                        WHERE status <> 'completed' AND (:idolId IS NULL OR idol_id = :idolId)
                        """)
                .param("idolId", idolId)
                .query((resultSet, rowNumber) -> resultSet.getObject("oldest", OffsetDateTime.class))
                .single();

        queue.put("backlog", queue.values().stream().mapToInt(value -> (int) value).sum());
        queue.put("oldestQueuedAt", oldest);
        return queue;
    }

    private static Map<String, Object> serializeDelivery(ResultSet resultSet) throws SQLException {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("postId", resultSet.getString("post_id"));
        // 只回传内部 user id，不回传 openid：排查推送不需要知道用户是谁。
        delivery.put("userId", resultSet.getString("user_id"));
        delivery.put("idolId", resultSet.getString("idol_id"));
        delivery.put("idolName", resultSet.getString("idol_name"));
        delivery.put("postTitle", resultSet.getString("title"));
        delivery.put("status", resultSet.getString("status"));
        delivery.put("errorCode", resultSet.getString("error_code"));
        delivery.put("attemptCount", resultSet.getInt("attempt_count"));
        delivery.put("createdAt", resultSet.getObject("created_at", OffsetDateTime.class));
        delivery.put("attemptedAt", resultSet.getObject("attempted_at", OffsetDateTime.class));
        delivery.put("finishedAt", resultSet.getObject("finished_at", OffsetDateTime.class));
        delivery.put("nextAttemptAt", resultSet.getObject("next_attempt_at", OffsetDateTime.class));
        delivery.put("firstOpenedAt", resultSet.getObject("first_opened_at", OffsetDateTime.class));
        delivery.put("openCount", resultSet.getInt("open_count"));
        return delivery;
    }

    private static Duration range(Integer rangeHours) {
        if (rangeHours == null) {
            return DEFAULT_RANGE;
        }
        Duration requested = Duration.ofHours(rangeHours);
        if (requested.isNegative() || requested.isZero() || requested.compareTo(MAX_RANGE) > 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "时间区间超出允许范围");
        }
        return requested;
    }

    private static String normalizeStatus(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || "all".equalsIgnoreCase(normalized)) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!DELIVERY_STATUS.contains(normalized) && !STUCK_FILTER.equals(normalized)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "无效的投递状态筛选值");
        }
        return normalized;
    }

    private static double ratio(int part, int total) {
        return total == 0 ? 0d : Math.round(part * 10000d / total) / 100d;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

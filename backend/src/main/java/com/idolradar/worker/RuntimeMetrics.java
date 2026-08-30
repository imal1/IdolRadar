package com.idolradar.worker;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 注册 API/Worker 共用的运行指标，并记录 Worker 每轮结果。 */
@Component
@ConditionalOnExpression("'${app.mode:api}' == 'api' or '${app.mode:api}' == 'worker'")
public class RuntimeMetrics {
    private static final List<String> DELIVERY_STATUSES = List.of(
            "reserved", "sending", "retryable", "sent", "failed", "uncertain");

    private final JdbcClient jdbc;
    private final Timer successfulRound;
    private final Timer failedRound;
    private final Timer skippedRound;
    private final Counter successfulSources;
    private final Counter failedSources;
    private final AtomicInteger databaseAvailable = new AtomicInteger();
    private volatile DatabaseSnapshot database = DatabaseSnapshot.unavailable();

    public RuntimeMetrics(MeterRegistry registry, JdbcClient jdbc) {
        this.jdbc = jdbc;
        successfulRound = roundTimer(registry, "success");
        failedRound = roundTimer(registry, "failed");
        skippedRound = roundTimer(registry, "skipped");
        successfulSources = sourceCounter(registry, "success");
        failedSources = sourceCounter(registry, "failed");

        DELIVERY_STATUSES.forEach(status -> Gauge.builder(
                        "idolradar.notification.deliveries",
                        () -> database.deliveries().get(status))
                .description("Persisted notification deliveries by status")
                .tag("status", status)
                .register(registry));
        Gauge.builder("idolradar.notification.outbox.pending", () -> database.pendingOutbox())
                .description("Notification outbox rows not yet completed")
                .register(registry);
        Gauge.builder("idolradar.database.metrics.available", databaseAvailable, AtomicInteger::get)
                .description("Whether the latest database metrics refresh succeeded")
                .register(registry);
    }

    public void recordWorkerRun(WorkerModels.WorkerRunResult result, long elapsedNanos) {
        (result.skipped() ? skippedRound : successfulRound).record(elapsedNanos, TimeUnit.NANOSECONDS);
        successfulSources.increment(result.sourcesSucceeded());
        failedSources.increment(result.sourcesFailed());
    }

    public void recordWorkerFailure(long elapsedNanos) {
        failedRound.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /** 后台刷新一次数据库快照；Prometheus 抓取线程只读内存，依赖故障时不会被连接超时拖住。 */
    @Scheduled(
            initialDelayString = "${idolradar.metrics.database-initial-delay:PT1S}",
            fixedDelayString = "${idolradar.metrics.database-refresh-interval:PT30S}")
    public void refreshDatabaseMetrics() {
        try {
            database = jdbc.sql("""
                            SELECT
                              (SELECT COUNT(*) FROM idr_notification_delivery WHERE status = 'reserved') reserved_count,
                              (SELECT COUNT(*) FROM idr_notification_delivery WHERE status = 'sending') sending_count,
                              (SELECT COUNT(*) FROM idr_notification_delivery WHERE status = 'retryable') retryable_count,
                              (SELECT COUNT(*) FROM idr_notification_delivery WHERE status = 'sent') sent_count,
                              (SELECT COUNT(*) FROM idr_notification_delivery WHERE status = 'failed') failed_count,
                              (SELECT COUNT(*) FROM idr_notification_delivery WHERE status = 'uncertain') uncertain_count,
                              (SELECT COUNT(*) FROM idr_notification_outbox WHERE status <> 'completed') pending_outbox
                            """)
                    .query(RuntimeMetrics::mapDatabaseSnapshot)
                    .single();
            databaseAvailable.set(1);
        } catch (RuntimeException unavailable) {
            // 保留最后一次成功快照；没有快照时为 NaN，并明确把可用性 gauge 置零。
            databaseAvailable.set(0);
        }
    }

    private static DatabaseSnapshot mapDatabaseSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        Map<String, Double> deliveries = new LinkedHashMap<>();
        for (String status : DELIVERY_STATUSES) {
            deliveries.put(status, resultSet.getDouble(status + "_count"));
        }
        return new DatabaseSnapshot(Map.copyOf(deliveries), resultSet.getDouble("pending_outbox"));
    }

    private static Timer roundTimer(MeterRegistry registry, String outcome) {
        return Timer.builder("idolradar.worker.round.duration")
                .description("Worker scheduled round duration")
                .tag("outcome", outcome)
                .register(registry);
    }

    private static Counter sourceCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("idolradar.worker.fetch.sources")
                .description("RSS sources processed by worker")
                .tag("outcome", outcome)
                .register(registry);
    }

    private record DatabaseSnapshot(Map<String, Double> deliveries, double pendingOutbox) {
        private static DatabaseSnapshot unavailable() {
            Map<String, Double> deliveries = new LinkedHashMap<>();
            DELIVERY_STATUSES.forEach(status -> deliveries.put(status, Double.NaN));
            return new DatabaseSnapshot(Map.copyOf(deliveries), Double.NaN);
        }
    }
}

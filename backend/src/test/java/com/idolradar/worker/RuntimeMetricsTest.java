package com.idolradar.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class RuntimeMetricsTest {

    @Test
    void recordsWorkerRoundDurationAndSourceResults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeMetrics metrics = new RuntimeMetrics(registry, mock(JdbcClient.class));
        WorkerModels.WorkerRunResult result = new WorkerModels.WorkerRunResult(
                false,
                null,
                5,
                3,
                2,
                1,
                new WorkerModels.Reconciliation(0, 0),
                WorkerModels.NotificationTotals.empty(),
                List.of());

        metrics.recordWorkerRun(result, TimeUnit.MILLISECONDS.toNanos(250));

        assertThat(registry.get("idolradar.worker.round.duration")
                .tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("idolradar.worker.fetch.sources")
                .tag("outcome", "success").counter().count()).isEqualTo(3);
        assertThat(registry.get("idolradar.worker.fetch.sources")
                .tag("outcome", "failed").counter().count()).isEqualTo(2);
    }

    @Test
    void databaseFailureDoesNotBreakPrometheusScrape() {
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenThrow(new IllegalStateException("database unavailable"));
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        RuntimeMetrics metrics = new RuntimeMetrics(registry, jdbc);
        metrics.recordWorkerFailure(TimeUnit.MILLISECONDS.toNanos(100));
        metrics.refreshDatabaseMetrics();
        clearInvocations(jdbc);

        String scrape = registry.scrape();

        assertThat(scrape).contains("idolradar_worker_round_duration_seconds_count");
        assertThat(scrape).contains("idolradar_notification_outbox_pending NaN");
        assertThat(scrape).contains("idolradar_database_metrics_available 0.0");
        assertThat(scrape).doesNotContain("openid");
        verifyNoInteractions(jdbc);
    }
}

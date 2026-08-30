package com.idolradar.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Docker Worker 常驻调度器；每轮仍由 PostgreSQL advisory lock 保证多实例不重入。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
@ConditionalOnProperty(name = "idolradar.worker.schedule-enabled", havingValue = "true")
public class WorkerScheduler {
    private static final Logger log = LoggerFactory.getLogger(WorkerScheduler.class);
    private final WorkerService worker;
    private final RuntimeMetrics metrics;

    public WorkerScheduler(WorkerService worker, RuntimeMetrics metrics) {
        this.worker = worker;
        this.metrics = metrics;
    }

    /**
     * 固定延迟避免慢任务堆叠；异常仅记录类型，既保护含凭据 URL，也保证后续轮次继续运行。
     */
    @Scheduled(
            initialDelayString = "${idolradar.worker.schedule-initial-delay:PT5S}",
            fixedDelayString = "${idolradar.worker.schedule-interval:PT30M}")
    public void runScheduled() {
        long startedAt = System.nanoTime();
        try {
            WorkerModels.WorkerRunResult result = worker.runOnce();
            metrics.recordWorkerRun(result, System.nanoTime() - startedAt);
            log.info(
                    "Worker 定时任务完成：sources={}, succeeded={}, posts={}",
                    result.sourcesTotal(),
                    result.sourcesSucceeded(),
                    result.postsInserted());
        } catch (RuntimeException error) {
            metrics.recordWorkerFailure(System.nanoTime() - startedAt);
            log.error("Worker 定时任务失败：{}", error.getClass().getSimpleName());
        }
    }
}

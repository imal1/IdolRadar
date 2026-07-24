package com.idolradar.worker;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 配额驱动 delivery 状态机与持久化 outbox 的存储协议。
 *
 * <p>实现必须原子创建 delivery 并扣减额度，以条件更新阻止并发 worker 重复推进状态。
 */
public interface NotificationRepository {
    Optional<WorkerModels.PostWithIdol> loadPostWithIdol(String postId);

    List<WorkerModels.UserTarget> loadEligibleUsers(
            String postId,
            String idolId,
            String templateId,
            UUID afterId,
            int limit);

    /** 在同一事务内创建首次 delivery，并扣减对应模板的一次额度。 */
    boolean claimDelivery(String postId, UUID userId, String idolId, String templateId);

    /** 在 HTTP POST 前持久化不可安全重试的 sending 边界。 */
    void markDeliverySending(String postId, UUID userId);

    /** 处理 POST 前失败：保留额度重试；耗尽后结束并退还额度。 */
    Optional<WorkerModels.RetrySchedule> scheduleReservedRetry(
            String postId,
            UUID userId,
            String errorCode,
            int maxAttempts,
            Duration baseDelay);

    /** 处理明确的 WeChat 全局错误：退还额度，并在尝试上限内重试。 */
    WorkerModels.RetrySchedule scheduleAttemptedRetry(
            String postId,
            UUID userId,
            String errorCode,
            int maxAttempts,
            Duration baseDelay);

    /** 处理用户或模板终态错误，并清空该模板额度。 */
    void failDeliveryAndClearQuota(String postId, UUID userId, String templateId, String errorCode);
    void finishDelivery(String postId, UUID userId, String status, String errorCode);
    List<WorkerModels.RetryDelivery> loadDueDeliveries(int limit);

    /**
     * 重新领取到期投递：锁定 delivery 与用户订阅后校验偶像、模板、尝试次数。
     * 已预留额度保持不变；未预留时只允许原子扣减一次；不兼容或耗尽则进入失败终态。
     */
    boolean claimRetryDelivery(
            WorkerModels.RetryDelivery delivery,
            String templateId,
            int maxAttempts);

    /** 恢复超时状态：reserved 可重试，sending 转 uncertain，禁止重复发送。 */
    WorkerModels.Reconciliation reconcileStaleDeliveries(Duration maxAge);

    /** 将 lease 已过期的 processing outbox 恢复为 retryable。 */
    int recoverStaleOutbox();

    /** 原子 claim 一条到期 outbox 并写入 lease，供多 worker 竞争消费。 */
    Optional<WorkerModels.OutboxTask> claimNextOutbox(Duration lease);

    /** 仅完成匹配 idol/post 的 processing 任务；旧任务不得覆盖新 post。 */
    boolean completeOutbox(WorkerModels.OutboxTask task);

    /** 仅重试匹配 idol/post 的 processing 任务；旧任务不得覆盖新 post。 */
    void retryOutbox(
            WorkerModels.OutboxTask task,
            String errorCode,
            int maxAttempts,
            Duration baseDelay);
}

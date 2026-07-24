package com.idolradar.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.idolradar.config.BackendProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 以有界并发将持久化 post fanout 为 WeChat 订阅消息。
 *
 * <p>发送前先占用额度并写入 delivery；HTTP POST 前切换 sending。
 * POST 前失败或明确的 WeChat 全局错误才进入重试；未知 POST 结果标记 uncertain，
 * 不自动重试或退还额度，保持 at-most-once。
 */
@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Set<Integer> USER_TERMINAL_CODES = Set.of(40003, 43101);
    private static final Set<Integer> GLOBAL_ABORT_CODES = Set.of(-1, 40037, 41030, 45009, 47003);
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final NotificationRepository repository;
    private final WechatGateway wechat;
    private final WorkerProperties properties;
    private final BackendProperties backendProperties;

    public NotificationService(
            NotificationRepository repository,
            WechatGateway wechat,
            WorkerProperties properties,
            BackendProperties backendProperties) {
        this.repository = repository;
        this.wechat = wechat;
        this.properties = properties;
        this.backendProperties = backendProperties;
    }

    public WorkerModels.NotificationTotals sendPost(String postId) {
        if (backendProperties.subscribeTemplateId().isBlank()) {
            throw new IllegalStateException("订阅消息模板未配置");
        }
        WorkerModels.PostWithIdol post = repository.loadPostWithIdol(postId)
                .orElseThrow(() -> new IllegalArgumentException("动态不存在"));
        WorkerModels.NotificationTotals totals = WorkerModels.NotificationTotals.empty();
        UUID afterId = null;
        while (true) {
            List<WorkerModels.UserTarget> users = repository.loadEligibleUsers(
                    post.id(), post.idolId(), backendProperties.subscribeTemplateId(), afterId, 100);
            if (users.isEmpty()) return totals;
            afterId = users.get(users.size() - 1).id();
            List<Supplier<WorkerModels.DeliveryOutcome>> tasks = users.stream()
                    .<Supplier<WorkerModels.DeliveryOutcome>>map(user -> () -> sendToUser(post, user, false))
                    .toList();
            for (WorkerModels.DeliveryOutcome outcome : runConcurrent(tasks)) totals = totals.add(outcome);
            if (users.size() < 100) return totals;
        }
    }

    public WorkerModels.NotificationTotals retryDueDeliveries() {
        WorkerModels.NotificationTotals totals = WorkerModels.NotificationTotals.empty();
        while (true) {
            List<WorkerModels.RetryDelivery> due = repository.loadDueDeliveries(100);
            if (due.isEmpty()) return totals;
            List<Supplier<WorkerModels.DeliveryOutcome>> tasks = due.stream()
                    .<Supplier<WorkerModels.DeliveryOutcome>>map(delivery -> () -> {
                        boolean claimed = repository.claimRetryDelivery(
                                delivery,
                                backendProperties.subscribeTemplateId(),
                                properties.getNotificationMaxAttempts());
                        return claimed
                                ? sendToUser(
                                        delivery.post(),
                                        new WorkerModels.UserTarget(delivery.userId(), delivery.openId()),
                                        true)
                                : WorkerModels.DeliveryOutcome.SKIPPED;
                    })
                    .toList();
            for (WorkerModels.DeliveryOutcome outcome : runConcurrent(tasks)) totals = totals.add(outcome);
            if (due.size() < 100) return totals;
        }
    }

    /** 持续 claim outbox lease；成功时条件完成，失败时先持久化重试再向上抛出。 */
    public List<WorkerModels.NotificationTotals> drainOutbox() {
        List<WorkerModels.NotificationTotals> totals = new ArrayList<>();
        while (true) {
            Optional<WorkerModels.OutboxTask> claimed = repository.claimNextOutbox(
                    properties.getNotificationLease());
            if (claimed.isEmpty()) return List.copyOf(totals);
            WorkerModels.OutboxTask task = claimed.get();
            try {
                WorkerModels.NotificationTotals result = sendPost(task.postId());
                repository.completeOutbox(task);
                totals.add(result);
            } catch (RuntimeException error) {
                String errorCode = error instanceof NotificationAbortException abort
                        ? "WECHAT_" + abort.wechatCode()
                        : "NOTIFICATION_FANOUT_FAILED";
                repository.retryOutbox(
                        task,
                        errorCode,
                        properties.getNotificationMaxAttempts(),
                        properties.getNotificationRetryBase());
                throw error;
            }
        }
    }

    /** 按配置上限执行一页 fanout；首个异常阻止尚未开始的任务。 */
    private List<WorkerModels.DeliveryOutcome> runConcurrent(
            List<Supplier<WorkerModels.DeliveryOutcome>> tasks) {
        if (tasks.isEmpty()) return List.of();
        AtomicReference<RuntimeException> stop = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(properties.getNotificationConcurrency(), tasks.size()),
                Thread.ofVirtual().name("idolradar-notify-", 0).factory())) {
            List<Future<WorkerModels.DeliveryOutcome>> futures = tasks.stream()
                    .map(task -> executor.submit(() -> {
                        if (stop.get() != null) return WorkerModels.DeliveryOutcome.SKIPPED;
                        try {
                            return task.get();
                        } catch (RuntimeException error) {
                            stop.compareAndSet(null, error);
                            throw error;
                        }
                    }))
                    .toList();
            List<WorkerModels.DeliveryOutcome> outcomes = new ArrayList<>(futures.size());
            for (Future<WorkerModels.DeliveryOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    stop.compareAndSet(null, new IllegalStateException("Notification fanout interrupted", error));
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof RuntimeException runtime) stop.compareAndSet(null, runtime);
                    else stop.compareAndSet(null, new IllegalStateException("Notification fanout failed", error.getCause()));
                }
            }
            if (stop.get() != null) throw stop.get();
            return outcomes;
        }
    }

    /** 执行单用户额度与 delivery 状态机；alreadyClaimed 表示重试流程已完成 claim。 */
    WorkerModels.DeliveryOutcome sendToUser(
            WorkerModels.PostWithIdol post,
            WorkerModels.UserTarget user,
            boolean alreadyClaimed) {
        String templateId = backendProperties.subscribeTemplateId();
        if (!alreadyClaimed && !repository.claimDelivery(post.id(), user.id(), post.idolId(), templateId)) {
            return WorkerModels.DeliveryOutcome.SKIPPED;
        }
        WorkerModels.SubscribeMessage message = buildMessage(post, user.openId());
        boolean[] attempted = {false};
        try {
            wechat.sendSubscribeMessage(message, () -> {
                repository.markDeliverySending(post.id(), user.id());
                attempted[0] = true;
            });
        } catch (RuntimeException error) {
            Integer wechatCode = error instanceof WechatException wechatError
                    ? wechatError.wechatCode()
                    : null;
            String errorCode = wechatCode == null ? "SEND_FAILED" : "WECHAT_" + wechatCode;
            if (!attempted[0]) {
                Optional<WorkerModels.RetrySchedule> scheduled = repository.scheduleReservedRetry(
                        post.id(),
                        user.id(),
                        errorCode,
                        properties.getNotificationMaxAttempts(),
                        properties.getNotificationRetryBase());
                log.warn("Notification failed before send: user={}, code={}", anonymize(user.openId()), errorCode);
                return scheduled.map(WorkerModels.RetrySchedule::retrying).orElse(false)
                        ? WorkerModels.DeliveryOutcome.RETRYING
                        : WorkerModels.DeliveryOutcome.FAILED;
            }

            if (wechatCode != null && USER_TERMINAL_CODES.contains(wechatCode)) {
                repository.failDeliveryAndClearQuota(post.id(), user.id(), templateId, errorCode);
            } else if (wechatCode != null && GLOBAL_ABORT_CODES.contains(wechatCode)) {
                repository.scheduleAttemptedRetry(
                        post.id(),
                        user.id(),
                        errorCode,
                        properties.getNotificationMaxAttempts(),
                        properties.getNotificationRetryBase());
                throw new NotificationAbortException(wechatCode);
            } else if (wechatCode != null) {
                repository.finishDelivery(post.id(), user.id(), "failed", errorCode);
            } else {
                // 无 WeChat 错误码时，HTTP POST 结果未知；重试或退还额度都可能造成重复发送。
                repository.finishDelivery(post.id(), user.id(), "uncertain", errorCode);
            }
            log.warn("Notification failed: user={}, code={}", anonymize(user.openId()), errorCode);
            return WorkerModels.DeliveryOutcome.FAILED;
        }

        try {
            repository.finishDelivery(post.id(), user.id(), "sent", null);
        } catch (RuntimeException error) {
            // WeChat 已受理请求；保留已消费额度，避免状态落库失败后重复发送。
            log.error("Notification accepted but status update failed: user={}", anonymize(user.openId()));
        }
        return WorkerModels.DeliveryOutcome.SENT;
    }

    WorkerModels.SubscribeMessage buildMessage(WorkerModels.PostWithIdol post, String openId) {
        Map<String, Map<String, String>> data = Map.of(
                "thing1", Map.of("value", truncateThing(post.idolName(), "爱豆")),
                "thing2", Map.of("value", truncateThing(post.title(), "有新动态")),
                "time3", Map.of("value", MESSAGE_TIME.format(post.publishedAt())));
        return new WorkerModels.SubscribeMessage(
                openId,
                backendProperties.subscribeTemplateId(),
                "pages/radar/index?postId=" + post.id(),
                data,
                properties.getMiniprogramState(),
                "zh_CN");
    }

    static String truncateThing(String value) {
        return truncateThing(value, "有新动态");
    }

    private static String truncateThing(String value, String fallback) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return fallback;
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= 20) return normalized;
        return normalized.substring(0, normalized.offsetByCodePoints(0, 20));
    }

    static String anonymize(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 6);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

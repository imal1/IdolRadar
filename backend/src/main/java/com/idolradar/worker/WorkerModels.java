package com.idolradar.worker;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** worker 各阶段及持久化边界之间传递的不可变值。 */
public final class WorkerModels {
    private WorkerModels() {}

    public record Source(String id, String idolId, String rssUrl, String channel) {}

    public record FeedEntry(String title, String summary, String link, Instant publishedAt) {}

    public record Post(
            String id,
            String idolId,
            String sourceId,
            String channel,
            String title,
            String summary,
            String link,
            Instant publishedAt,
            Instant fetchedAt) {}

    public record PostWithIdol(
            String id,
            String idolId,
            String idolName,
            String title,
            Instant publishedAt) {}

    public record UserTarget(UUID id, String openId) {}

    public record RetryDelivery(PostWithIdol post, UUID userId, String openId) {}

    /** 已 claim outbox 的稳定任务标识及当前尝试次数。 */
    public record OutboxTask(String idolId, String postId, int attemptCount) {}

    public record SourceStatus(String status, String errorCode, int itemCount, int newCount) {
        public static SourceStatus success(int itemCount, int newCount) {
            return new SourceStatus("success", null, itemCount, newCount);
        }

        public static SourceStatus failed(String errorCode, int newCount) {
            return new SourceStatus("failed", errorCode, 0, newCount);
        }
    }

    public record SourceResult(boolean ok, List<Post> newPosts) {}

    public record Reconciliation(int retrying, int uncertain) {}

    public record RetrySchedule(boolean retrying) {}

    public record NotificationTotals(int eligible, int sent, int failed, int retrying, int skipped) {
        public static NotificationTotals empty() {
            return new NotificationTotals(0, 0, 0, 0, 0);
        }

        public NotificationTotals add(DeliveryOutcome outcome) {
            return switch (outcome) {
                case SENT -> new NotificationTotals(eligible + 1, sent + 1, failed, retrying, skipped);
                case FAILED -> new NotificationTotals(eligible + 1, sent, failed + 1, retrying, skipped);
                case RETRYING -> new NotificationTotals(eligible + 1, sent, failed, retrying + 1, skipped);
                case SKIPPED -> new NotificationTotals(eligible + 1, sent, failed, retrying, skipped + 1);
            };
        }

        public NotificationTotals plus(NotificationTotals other) {
            return new NotificationTotals(
                    eligible + other.eligible,
                    sent + other.sent,
                    failed + other.failed,
                    retrying + other.retrying,
                    skipped + other.skipped);
        }
    }

    public enum DeliveryOutcome { SENT, FAILED, RETRYING, SKIPPED }

    public record SubscribeMessage(
            String touser,
            String templateId,
            String page,
            Map<String, Map<String, String>> data,
            String miniprogramState,
            String lang) {}

    public record WorkerRunResult(
            boolean skipped,
            String reason,
            int sourcesTotal,
            int sourcesSucceeded,
            int sourcesFailed,
            int postsInserted,
            Reconciliation reconciliation,
            NotificationTotals retries,
            List<NotificationTotals> notifications) {
        public static WorkerRunResult alreadyRunning() {
            return new WorkerRunResult(true, "already_running", 0, 0, 0, 0,
                    new Reconciliation(0, 0), NotificationTotals.empty(), List.of());
        }
    }
}

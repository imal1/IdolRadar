package com.idolradar.worker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.idolradar.config.BackendProperties;
import com.idolradar.config.WechatProperties;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 在 PostgreSQL advisory lock 下协调一次生产 worker 运行。
 *
 * <p>RSS source 由固定上限线程池并发抓取；新 post 原子写入 outbox。
 * retry/outbox 均从 DB 恢复并消费，不依赖本轮是否抓到新 post。
 */
@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class WorkerService {
    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);
    private static final String LOCK_KEY = "idolradar:fetch-feeds";
    private static final Set<String> PUBLIC_FEED_ERRORS = Set.of(
            "DNS_LOOKUP_FAILED",
            "EMPTY_FEED",
            "FETCH_TIMEOUT",
            "HTTP_ERROR",
            "INVALID_FEED_URL",
            "INVALID_LINK",
            "INVALID_SOURCE",
            "INVALID_XML",
            "RESPONSE_TOO_LARGE",
            "TOO_MANY_REDIRECTS",
            "UNSAFE_FEED_URL",
            "UNSAFE_XML",
            "UNSUPPORTED_ENCODING",
            "UNSUPPORTED_FEED");

    private final DataSource dataSource;
    private final FeedRepository feeds;
    private final FeedDownloader downloader;
    private final FeedParser parser;
    private final NotificationRepository notificationsRepository;
    private final NotificationService notifications;
    private final WorkerProperties properties;
    private final BackendProperties backendProperties;
    private final WechatProperties wechatProperties;

    public WorkerService(
            DataSource dataSource,
            FeedRepository feeds,
            FeedDownloader downloader,
            FeedParser parser,
            NotificationRepository notificationsRepository,
            NotificationService notifications,
            WorkerProperties properties,
            BackendProperties backendProperties,
            WechatProperties wechatProperties) {
        this.dataSource = dataSource;
        this.feeds = feeds;
        this.downloader = downloader;
        this.parser = parser;
        this.notificationsRepository = notificationsRepository;
        this.notifications = notifications;
        this.properties = properties;
        this.backendProperties = backendProperties;
        this.wechatProperties = wechatProperties;
    }

    /**
     * 校验配置、恢复中断状态、抓取 source，再消费到期 delivery 与 outbox。
     *
     * <p>不负责调度或退出 JVM；已有实例持锁时返回 {@code already_running}。
     */
    public WorkerModels.WorkerRunResult runOnce() {
        properties.validateForRun();
        wechatProperties.validateForApi();
        if (!"https".equalsIgnoreCase(wechatProperties.apiBaseUrl().getScheme())) {
            throw new IllegalStateException("idolradar.wechat.api-base-url must use HTTPS in worker mode");
        }
        if (backendProperties.subscribeTemplateId().isBlank()) {
            throw new IllegalStateException("idolradar.subscribe-template-id is required in worker mode");
        }
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection)) return WorkerModels.WorkerRunResult.alreadyRunning();
            try {
                WorkerModels.Reconciliation reconciliation = notificationsRepository
                        .reconcileStaleDeliveries(properties.getNotificationLease());
                int recoveredOutbox = notificationsRepository.recoverStaleOutbox();
                if (recoveredOutbox > 0) {
                    log.warn("Recovered stale notification outbox rows: count={}", recoveredOutbox);
                }
                List<WorkerModels.Source> sources = feeds.loadEnabledSources();
                if (sources.isEmpty()) {
                    notifications.retryDueDeliveries();
                    notifications.drainOutbox();
                    throw new IllegalStateException("NO_ENABLED_SOURCES");
                }
                List<WorkerModels.SourceResult> results = processSources(sources);
                long succeeded = results.stream().filter(WorkerModels.SourceResult::ok).count();
                List<WorkerModels.Post> inserted = results.stream()
                        .flatMap(result -> result.newPosts().stream())
                        .toList();
                WorkerModels.NotificationTotals retryTotals = notifications.retryDueDeliveries();
                List<WorkerModels.NotificationTotals> notificationTotals = notifications.drainOutbox();
                if (succeeded == 0) {
                    throw new FeedException("FEED_RUN_FAILED", "所有数据源抓取失败");
                }
                return new WorkerModels.WorkerRunResult(
                        false,
                        null,
                        sources.size(),
                        Math.toIntExact(succeeded),
                        sources.size() - Math.toIntExact(succeeded),
                        inserted.size(),
                        reconciliation,
                        retryTotals,
                        List.copyOf(notificationTotals));
            } finally {
                unlock(lockConnection);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Worker database lock failed", error);
        }
    }

    private List<WorkerModels.SourceResult> processSources(List<WorkerModels.Source> sources) {
        try (ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(properties.getRssSourceConcurrency(), sources.size()),
                Thread.ofVirtual().name("idolradar-rss-", 0).factory())) {
            List<Future<WorkerModels.SourceResult>> futures = sources.stream()
                    .map(source -> executor.submit(() -> processSource(source)))
                    .toList();
            List<WorkerModels.SourceResult> results = new ArrayList<>(futures.size());
            for (Future<WorkerModels.SourceResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("RSS worker interrupted", error);
                } catch (ExecutionException error) {
                    throw new IllegalStateException("RSS worker task failed", error.getCause());
                }
            }
            return results;
        }
    }

    WorkerModels.SourceResult processSource(WorkerModels.Source source) {
        Instant fetchedAt = Instant.now();
        List<WorkerModels.Post> inserted = new ArrayList<>();
        try {
            validateSource(source);
            List<WorkerModels.FeedEntry> entries = parser.parse(downloader.fetch(source.rssUrl()), fetchedAt)
                    .stream()
                    .sorted(Comparator.comparing(WorkerModels.FeedEntry::publishedAt).reversed())
                    .limit(properties.getRssMaxEntriesPerSource())
                    .toList();
            for (WorkerModels.FeedEntry entry : entries) {
                WorkerModels.Post post = new WorkerModels.Post(
                        parser.postIdForLink(entry.link()),
                        source.idolId(),
                        source.id(),
                        normalizeChannel(source.channel()),
                        entry.title(),
                        entry.summary(),
                        entry.link(),
                        entry.publishedAt(),
                        fetchedAt);
                feeds.insertPostAndEnqueue(post).ifPresent(inserted::add);
            }
            feeds.updateSourceStatus(source.id(), WorkerModels.SourceStatus.success(entries.size(), inserted.size()));
            return new WorkerModels.SourceResult(true, List.copyOf(inserted));
        } catch (RuntimeException error) {
            String code = publicErrorCode(error);
            if (source != null && source.id() != null && !source.id().isBlank()) {
                try {
                    feeds.updateSourceStatus(source.id(), WorkerModels.SourceStatus.failed(code, inserted.size()));
                } catch (RuntimeException statusError) {
                    log.error("Failed to update RSS source status: source={}", source.id());
                }
            }
            log.warn("RSS source failed: source={}, code={}", source == null ? "unknown" : source.id(), code);
            return new WorkerModels.SourceResult(false, List.copyOf(inserted));
        }
    }

    private static void validateSource(WorkerModels.Source source) {
        if (source == null
                || source.id() == null || source.id().isBlank()
                || source.idolId() == null || source.idolId().isBlank()
                || source.rssUrl() == null || source.rssUrl().isBlank()) {
            throw new FeedException("INVALID_SOURCE", "Invalid source");
        }
    }

    private static String normalizeChannel(String channel) {
        String value = channel == null || channel.isBlank() ? "RSS" : channel.trim();
        int count = value.codePointCount(0, value.length());
        return count <= 32 ? value : value.substring(0, value.offsetByCodePoints(0, 32));
    }

    private static String publicErrorCode(RuntimeException error) {
        if (error instanceof FeedException feedError && PUBLIC_FEED_ERRORS.contains(feedError.code())) {
            return feedError.code();
        }
        return "INTERNAL_ERROR";
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_unlock(hashtext(?))")) {
            statement.setString(1, LOCK_KEY);
            statement.execute();
        } catch (SQLException error) {
            log.error("Failed to release RSS worker advisory lock");
        }
    }
}

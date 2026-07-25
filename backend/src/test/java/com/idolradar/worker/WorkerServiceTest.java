package com.idolradar.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idolradar.config.BackendProperties;
import com.idolradar.config.WechatProperties;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class WorkerServiceTest {
    @Test
    void transformsRssHubWeiboEntryBeforePersistence() {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>王一博·YIBO的微博</title>
                  <item>
                    <title><![CDATA[一条微博动态]]></title>
                    <description><![CDATA[<p>正文 &amp; 内容</p>]]></description>
                    <link>https://weibo.com/5492443184/P123456</link>
                    <pubDate>Sat, 25 Jul 2026 08:30:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """;
        FeedRepository feeds = mock(FeedRepository.class);
        when(feeds.insertPostAndEnqueue(any())).thenAnswer(call -> Optional.of(call.getArgument(0)));
        FeedDownloader downloader = url -> rss.getBytes(StandardCharsets.UTF_8);
        WorkerService service = new WorkerService(
                mock(DataSource.class),
                feeds,
                downloader,
                new FeedParser(),
                mock(NotificationRepository.class),
                mock(NotificationService.class),
                workerProperties(),
                new BackendProperties(Duration.ofDays(30), "template-1"),
                new WechatProperties(
                        "app-id", "app-secret", URI.create("https://api.weixin.qq.com"), Duration.ofSeconds(10)));

        WorkerModels.SourceResult result = service.processSource(new WorkerModels.Source(
                "source_wang_yibo_weibo",
                "idol_wang_yibo",
                "http://127.0.0.1:1200/weibo/user/5492443184",
                "微博"));

        assertThat(result.ok()).isTrue();
        assertThat(result.newPosts()).singleElement().satisfies(post -> {
            assertThat(post.idolId()).isEqualTo("idol_wang_yibo");
            assertThat(post.channel()).isEqualTo("微博");
            assertThat(post.title()).isEqualTo("一条微博动态");
            assertThat(post.summary()).isEqualTo("正文 & 内容");
            assertThat(post.link()).isEqualTo("https://weibo.com/5492443184/P123456");
        });
        verify(feeds).updateSourceStatus(
                "source_wang_yibo_weibo", WorkerModels.SourceStatus.success(1, 1));
    }

    @Test
    void drainsPersistedOutboxEvenWhenNoFeedSourceIsEnabled() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true);

        FeedRepository feeds = mock(FeedRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        WorkerProperties properties = workerProperties();
        when(notificationRepository.reconcileStaleDeliveries(properties.getNotificationLease()))
                .thenReturn(new WorkerModels.Reconciliation(0, 0));
        when(notifications.retryDueDeliveries()).thenReturn(WorkerModels.NotificationTotals.empty());
        when(feeds.loadEnabledSources()).thenReturn(List.of());
        when(notifications.drainOutbox()).thenReturn(List.of(WorkerModels.NotificationTotals.empty()));

        WorkerService service = new WorkerService(
                dataSource,
                feeds,
                mock(FeedDownloader.class),
                new FeedParser(),
                notificationRepository,
                notifications,
                properties,
                new BackendProperties(Duration.ofDays(30), "template-1"),
                new WechatProperties(
                        "app-id", "app-secret", URI.create("https://api.weixin.qq.com"), Duration.ofSeconds(10)));

        assertThatThrownBy(service::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NO_ENABLED_SOURCES");
        verify(notificationRepository).recoverStaleOutbox();
        verify(notifications).drainOutbox();
    }

    private static WorkerProperties workerProperties() {
        WorkerProperties properties = new WorkerProperties();
        properties.setWechatAppId("app-id");
        properties.setWechatAppSecret("app-secret");
        properties.setWechatApiBaseUrl(URI.create("https://api.weixin.qq.com"));
        properties.setSubscribeTemplateId("template-1");
        return properties;
    }
}

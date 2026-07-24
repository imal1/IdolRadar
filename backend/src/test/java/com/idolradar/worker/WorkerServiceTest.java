package com.idolradar.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class WorkerServiceTest {
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

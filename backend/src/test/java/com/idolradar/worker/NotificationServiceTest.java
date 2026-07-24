package com.idolradar.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.idolradar.config.BackendProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final WechatGateway wechat = mock(WechatGateway.class);
    private final WorkerProperties properties = new WorkerProperties();
    private NotificationService service;
    private WorkerModels.PostWithIdol post;
    private WorkerModels.UserTarget user;

    @BeforeEach
    void setUp() {
        properties.setNotificationMaxAttempts(5);
        properties.setNotificationRetryBase(Duration.ofSeconds(1));
        service = new NotificationService(
                repository,
                wechat,
                properties,
                new BackendProperties(Duration.ofDays(30), "template-1"));
        post = new WorkerModels.PostWithIdol(
                "post-1", "idol-1", "爱豆", "一条新动态", Instant.parse("2026-01-01T00:00:00Z"));
        user = new WorkerModels.UserTarget(UUID.randomUUID(), "openid-secret");
        when(repository.claimDelivery(post.id(), user.id(), post.idolId(), "template-1")).thenReturn(true);
    }

    @Test
    void retriesWithReservedQuotaWhenFailureOccursBeforePost() {
        doThrow(new WechatException("token unavailable", null))
                .when(wechat).sendSubscribeMessage(any(), any());
        when(repository.scheduleReservedRetry(
                eq(post.id()), eq(user.id()), eq("SEND_FAILED"), eq(5), eq(Duration.ofSeconds(1))))
                .thenReturn(Optional.of(new WorkerModels.RetrySchedule(true)));

        WorkerModels.DeliveryOutcome outcome = service.sendToUser(post, user, false);

        assertThat(outcome).isEqualTo(WorkerModels.DeliveryOutcome.RETRYING);
        verify(repository, never()).markDeliverySending(any(), any());
        verify(repository, never()).finishDelivery(any(), any(), any(), any());
    }

    @Test
    void marksUnknownPostResultUncertainWithoutRetryOrRefund() {
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            throw new WechatException("connection reset", null);
        }).when(wechat).sendSubscribeMessage(any(), any());

        WorkerModels.DeliveryOutcome outcome = service.sendToUser(post, user, false);

        assertThat(outcome).isEqualTo(WorkerModels.DeliveryOutcome.FAILED);
        verify(repository).markDeliverySending(post.id(), user.id());
        verify(repository).finishDelivery(post.id(), user.id(), "uncertain", "SEND_FAILED");
        verify(repository, never()).scheduleAttemptedRetry(any(), any(), any(), any(Integer.class), any());
        verify(repository, never()).failDeliveryAndClearQuota(any(), any(), any(), any());
    }

    @Test
    void clearsOnlyMatchingTemplateQuotaForTerminalUserError() {
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            throw new WechatException("rejected", 43101);
        }).when(wechat).sendSubscribeMessage(any(), any());

        service.sendToUser(post, user, false);

        verify(repository).failDeliveryAndClearQuota(
                post.id(), user.id(), "template-1", "WECHAT_43101");
    }

    @Test
    void globalTemplateErrorSchedulesSafeRetryAndAbortsFanout() {
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            throw new WechatException("bad template", 40037);
        }).when(wechat).sendSubscribeMessage(any(), any());
        when(repository.scheduleAttemptedRetry(
                post.id(), user.id(), "WECHAT_40037", 5, Duration.ofSeconds(1)))
                .thenReturn(new WorkerModels.RetrySchedule(true));

        assertThatThrownBy(() -> service.sendToUser(post, user, false))
                .isInstanceOf(NotificationAbortException.class)
                .extracting(error -> ((NotificationAbortException) error).wechatCode())
                .isEqualTo(40037);
        verify(repository).scheduleAttemptedRetry(
                post.id(), user.id(), "WECHAT_40037", 5, Duration.ofSeconds(1));
    }

    @Test
    void neverRetriesAcceptedMessageWhenFinalStatusWriteFails() {
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(wechat).sendSubscribeMessage(any(), any());
        doThrow(new IllegalStateException("db unavailable"))
                .when(repository).finishDelivery(post.id(), user.id(), "sent", null);

        WorkerModels.DeliveryOutcome outcome = service.sendToUser(post, user, false);

        assertThat(outcome).isEqualTo(WorkerModels.DeliveryOutcome.SENT);
        verify(repository, never()).scheduleReservedRetry(any(), any(), any(), any(Integer.class), any());
        verify(repository, never()).scheduleAttemptedRetry(any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void drainsPersistedOutboxAndCompletesClaimedPost() {
        WorkerModels.OutboxTask task = new WorkerModels.OutboxTask(post.idolId(), post.id(), 1);
        when(repository.claimNextOutbox(properties.getNotificationLease()))
                .thenReturn(Optional.of(task), Optional.empty());
        when(repository.loadPostWithIdol(post.id())).thenReturn(Optional.of(post));
        when(repository.loadEligibleUsers(post.id(), post.idolId(), "template-1", null, 100))
                .thenReturn(List.of());
        when(repository.completeOutbox(task)).thenReturn(true);

        List<WorkerModels.NotificationTotals> totals = service.drainOutbox();

        assertThat(totals).containsExactly(WorkerModels.NotificationTotals.empty());
        verify(repository).completeOutbox(task);
        verify(repository, never()).retryOutbox(any(), any(), any(Integer.class), any());
    }

    @Test
    void globalFailurePersistsOutboxRetryForNextWorkerRun() {
        WorkerModels.OutboxTask task = new WorkerModels.OutboxTask(post.idolId(), post.id(), 2);
        when(repository.claimNextOutbox(properties.getNotificationLease())).thenReturn(Optional.of(task));
        when(repository.loadPostWithIdol(post.id())).thenReturn(Optional.of(post));
        when(repository.loadEligibleUsers(post.id(), post.idolId(), "template-1", null, 100))
                .thenReturn(List.of(user));
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            throw new WechatException("bad template", 40037);
        }).when(wechat).sendSubscribeMessage(any(), any());
        when(repository.scheduleAttemptedRetry(
                post.id(), user.id(), "WECHAT_40037", 5, Duration.ofSeconds(1)))
                .thenReturn(new WorkerModels.RetrySchedule(true));

        assertThatThrownBy(service::drainOutbox).isInstanceOf(NotificationAbortException.class);

        verify(repository).retryOutbox(task, "WECHAT_40037", 5, Duration.ofSeconds(1));
        verify(repository, never()).completeOutbox(task);
    }
}

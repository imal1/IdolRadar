package com.idolradar.worker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class WorkerSchedulerTest {

    @Test
    void keepsFutureScheduleAliveAfterOneRunFails() {
        WorkerService worker = mock(WorkerService.class);
        when(worker.runOnce()).thenThrow(new IllegalStateException("secret-bearing failure"));

        // 调度方法必须吞掉单轮异常，否则 Spring 会停止后续固定延迟执行。
        assertThatCode(() -> new WorkerScheduler(worker).runScheduled()).doesNotThrowAnyException();
        verify(worker).runOnce();
    }
}

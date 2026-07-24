package com.idolradar.web;

import java.time.Duration;

/** 共享限流契约，保证水平扩展的 API 实例作出一致决策。 */
public interface DistributedRateLimiter {
    /** 在指定作用域与主体的固定窗口内原子消耗一次请求额度。 */
    boolean allow(String scope, String subject, int limit, Duration window);
}

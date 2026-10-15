package com.nlolobserve.query.service;

import com.nlolobserve.query.config.QueryProperties;
import org.springframework.stereotype.Component;

/**
 * 运维视图限流桶（详设 §1.4：/metrics/summary 不带 customerId 时 60 次/分）。
 * 固定窗口计数（骨架实现，进程内；多实例部署时换网关/集中式计数为运维项）。
 */
@Component
public class OpsViewRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final QueryProperties properties;
    private final Object lock = new Object();

    private long windowStartMs;
    private int count;

    public OpsViewRateLimiter(QueryProperties properties) {
        this.properties = properties;
    }

    /** 尝试取一个配额：窗口内未超限返回 true 并计数，否则 false。 */
    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (now - windowStartMs >= WINDOW_MS) {
                windowStartMs = now;
                count = 0;
            }
            if (count >= properties.getOpsViewRateLimitPerMin()) {
                return false;
            }
            count++;
            return true;
        }
    }
}

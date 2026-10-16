package com.nlolobserve.query.service;

import com.nlolobserve.query.config.QueryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 运维视图限流桶（详设 §1.4：/metrics/summary 不带 customerId 时 60 次/分）。
 * 固定窗口计数（骨架实现，进程内；多实例部署时换网关/集中式计数为运维项）。
 * 时间源可注入（评审 M-1）：验收测试用固定时钟 → 窗口永不推进，超频断言
 * 不再依赖「测试循环墙钟耗时 &lt; 60s」这一未声明前提（慢 CI 上必然误报）。
 */
@Component
public class OpsViewRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final QueryProperties properties;
    private final LongSupplier nowMillis;
    private final Object lock = new Object();

    private long windowStartMs;
    private int count;

    @Autowired
    public OpsViewRateLimiter(QueryProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    /** 可注入时间源构造（测试：固定时钟 → 固定窗口永不滚动，判定确定化）。 */
    public OpsViewRateLimiter(QueryProperties properties, LongSupplier nowMillis) {
        // 评审 m5：限额 <1 会把运维视图静默完全封禁，启动 fail-fast
        if (properties.getOpsViewRateLimitPerMin() < 1) {
            throw new IllegalStateException(
                    "observe.ops-view-rate-limit-per-min 必须 >= 1（当前 "
                            + properties.getOpsViewRateLimitPerMin() + "）");
        }
        this.properties = properties;
        this.nowMillis = nowMillis;
    }

    /** 尝试取一个配额：窗口内未超限返回 true 并计数，否则 false。 */
    public boolean tryAcquire() {
        long now = nowMillis.getAsLong();
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

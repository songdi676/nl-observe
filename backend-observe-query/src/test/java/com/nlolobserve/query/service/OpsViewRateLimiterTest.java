package com.nlolobserve.query.service;

import com.nlolobserve.query.config.QueryProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T09：运维视图限流桶（详设 §1.4：/metrics/summary 不带 customerId 时 60 次/分）。
 */
class OpsViewRateLimiterTest {

    private static OpsViewRateLimiter limiter(int perMin) {
        QueryProperties props = new QueryProperties();
        props.setOpsViewRateLimitPerMin(perMin);
        return new OpsViewRateLimiter(props);
    }

    @Test
    void permitsUpToLimitThenBlocks() {
        OpsViewRateLimiter limiter = limiter(2);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void zeroLimit_blocksImmediately() {
        assertThat(limiter(0).tryAcquire()).isFalse();
    }

    @Test
    void defaultLimitIs60PerMin() {
        assertThat(new QueryProperties().getOpsViewRateLimitPerMin()).isEqualTo(60);
    }
}

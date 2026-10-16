package com.nlolobserve.receiver.filter;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T06：滑动窗口限流器单测（确定性时钟）——窗口内计数、只计放行请求、
 * 窗口滑动后恢复、key 相互独立、非法上限 fail-fast。
 */
class SlidingWindowRateLimiterTest {

    /** 可推进的假时钟 */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private final AtomicLong millis = new AtomicLong(0);

        void advance(long deltaMillis) {
            millis.addAndGet(deltaMillis);
        }

        @Override
        public long getAsLong() {
            return millis.get();
        }
    }

    @Test
    void windowFillsThenRejects() {
        FakeClock clock = new FakeClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).as("窗口内第 4 次应拒绝").isFalse();
        clock.advance(30_000);
        assertThat(limiter.tryAcquire("1.1.1.1")).as("窗口未滑动，仍应拒绝").isFalse();
    }

    @Test
    void rejectedRequestsDoNotConsumeQuota() {
        FakeClock clock = new FakeClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        // 拒绝不得自我锁定：窗口滑动后应恢复放行
        clock.advance(61_000);
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
    }

    @Test
    void windowSlidesAfterSixtySeconds() {
        FakeClock clock = new FakeClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        // 恰好 60s：最老条目出窗（<= 边界含等于）
        clock.advance(60_000);
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
    }

    @Test
    void keysAreIndependent() {
        FakeClock clock = new FakeClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("2.2.2.2")).as("不同 key 互不影响").isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        assertThat(limiter.keyCount()).isEqualTo(2);
    }

    @Test
    void evictIdleRemovesOnlyFullyExpiredKeys() {
        // 评审 F2：窗口表须可回收长期不再出现的源 IP（否则内存单调增长）
        FakeClock clock = new FakeClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, clock);

        assertThat(limiter.tryAcquire("old")).isTrue();   // t=0
        clock.advance(40_000);
        assertThat(limiter.tryAcquire("fresh")).isTrue(); // t=40s
        clock.advance(25_000);                            // t=65s：old 出窗（>60s），fresh 仍在窗内
        limiter.evictIdle();

        assertThat(limiter.keyCount()).as("仅全过期 key 被清扫，活跃 key 保留").isEqualTo(1);
        assertThat(limiter.tryAcquire("fresh")).as("活跃 key 不受清扫影响").isTrue();
        assertThat(limiter.tryAcquire("old")).as("被清扫的 key 可重新建窗").isTrue();
    }

    @Test
    void zeroOrNegativeLimit_failsFast() {
        assertThatThrownBy(() -> new SlidingWindowRateLimiter(0, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowRateLimiter(-5, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

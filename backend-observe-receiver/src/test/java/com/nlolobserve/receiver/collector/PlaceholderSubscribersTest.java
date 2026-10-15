package com.nlolobserve.receiver.collector;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T04 占位订阅者行为：onSubscribe 必须立即 request，否则批处理 future 挂起导致
 * OTLP 入口 10s 超时（collector-embedded 硬约束，T01 poc-notes §1）。
 */
class PlaceholderSubscribersTest {

    @Test
    void spansSubscriber_requestsUnboundedAndSurvivesError() {
        PlaceholderSpansSubscriber subscriber = new PlaceholderSpansSubscriber();
        AtomicLong requested = new AtomicLong(-1);
        subscriber.onSubscribe(requestsAll(requested));
        assertThat(requested.get()).isEqualTo(Long.MAX_VALUE);
        subscriber.onNext(null);
        assertThatCode(() -> subscriber.onError(new IllegalStateException("模拟分发失败")))
                .doesNotThrowAnyException();
        subscriber.onComplete();
    }

    @Test
    void metricsSubscriber_requestsUnboundedAndSurvivesError() {
        PlaceholderMetricsSubscriber subscriber = new PlaceholderMetricsSubscriber();
        AtomicLong requested = new AtomicLong(-1);
        subscriber.onSubscribe(requestsAll(requested));
        assertThat(requested.get()).isEqualTo(Long.MAX_VALUE);
        subscriber.onNext(null);
        assertThatCode(() -> subscriber.onError(new IllegalStateException("模拟分发失败")))
                .doesNotThrowAnyException();
        subscriber.onComplete();
    }

    private static Flow.Subscription requestsAll(AtomicLong requested) {
        return new Flow.Subscription() {
            @Override
            public void request(long n) {
                requested.set(n);
            }

            @Override
            public void cancel() {
            }
        };
    }
}

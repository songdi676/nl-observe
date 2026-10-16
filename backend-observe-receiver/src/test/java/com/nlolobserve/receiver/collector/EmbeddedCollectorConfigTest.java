package com.nlolobserve.receiver.collector;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.Flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.receiver.config.OtlpProperties;
import com.nlolobserve.receiver.config.SecurityFilterProperties;
import com.nlolobserve.receiver.metrics.RejectedCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.mishmash.opentelemetry.server.collector.Log;
import io.mishmash.opentelemetry.server.collector.LogsSubscriber;
import io.mishmash.opentelemetry.server.collector.MetricDataPoint;
import io.mishmash.opentelemetry.server.collector.MetricsSubscriber;
import io.mishmash.opentelemetry.server.collector.SpansSubscriber;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T04 内嵌 collector 生命周期（单测部分；端口监听与释放由集成用例覆盖）。
 * 验收：observe.otlp.port 可配置；监听失败（端口被占用）时快速失败并回滚资源，不泄漏。
 * T05 后订阅者改为 Kafka 写入订阅者——本单测不经 Spring 上下文，直接注入 no-op 订阅者。
 * T06 构造新增安全过滤器依赖（默认属性：白名单 10.0.0.0/8、10MB、600/min）。
 */
class EmbeddedCollectorConfigTest {

    @Test
    void start_portInUse_failsFastAndReleasesResources() throws IOException {
        try (ServerSocket blocker = new ServerSocket(0)) {
            OtlpProperties properties = new OtlpProperties();
            properties.setPort(blocker.getLocalPort());
            EmbeddedCollectorConfig config = newConfig(properties);

            assertThatThrownBy(config::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OTLP/HTTP listen");
        }
        // 回滚后再次 stop 应安全无操作（资源已释放）
        EmbeddedCollectorConfig config = newConfig(new OtlpProperties());
        assertThatCode(config::stop).doesNotThrowAnyException();
    }

    private static EmbeddedCollectorConfig newConfig(OtlpProperties properties) {
        return new EmbeddedCollectorConfig(properties, new SecurityFilterProperties(),
                noopSpans(), noopMetrics(), noopLogs(),
                new RejectedCounter(new SimpleMeterRegistry()), new ObjectMapper());
    }

    private static SpansSubscriber noopSpans() {
        return new SpansSubscriber() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(io.mishmash.opentelemetry.server.collector.Span span) {
                // no-op
            }

            @Override
            public void onError(Throwable throwable) {
                // no-op
            }

            @Override
            public void onComplete() {
                // no-op
            }
        };
    }

    private static MetricsSubscriber noopMetrics() {
        return new MetricsSubscriber() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(MetricDataPoint dataPoint) {
                // no-op
            }

            @Override
            public void onError(Throwable throwable) {
                // no-op
            }

            @Override
            public void onComplete() {
                // no-op
            }
        };
    }

    private static LogsSubscriber noopLogs() {
        return new LogsSubscriber() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Log log) {
                // no-op
            }

            @Override
            public void onError(Throwable throwable) {
                // no-op
            }

            @Override
            public void onComplete() {
                // no-op
            }
        };
    }
}

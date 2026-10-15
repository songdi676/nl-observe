package com.nlolobserve.receiver.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.protobuf.util.JsonFormat;
import com.nlolobserve.receiver.config.KafkaExporterProperties;
import com.nlolobserve.receiver.metrics.DroppedCounter;
import io.mishmash.opentelemetry.server.collector.Batch;
import io.mishmash.opentelemetry.server.collector.Log;
import io.mishmash.opentelemetry.server.collector.MetricDataPoint;
import io.mishmash.opentelemetry.server.collector.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.metrics.v1.Gauge;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * T05（单元）：Kafka 三 topic 写入订阅者——路由 topic、key=nl.customer_id、载荷 OTLP/JSON，
 * 以及 AC-10 丢弃计数路径与不向 collector 传播异常。
 * 注意（T05 修复轮，评审 Critical-1）：send 卸载到订阅者内专用有界队列执行器异步执行，
 * 因此对 mock kafka 的交互与 dropped 断言一律用 timeout/await 轮询口径。
 */
class KafkaSubscribersTest {

    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final KafkaExporterProperties props = new KafkaExporterProperties();
    private final DroppedCounter dropped = new DroppedCounter(new io.micrometer.core.instrument
            .simple.SimpleMeterRegistry());

    @Test
    void spansSubscriber_routesToTracesTopic_withCustomerIdKey_andOtlpJsonPayload() {
        completedSend();
        KafkaSpansSubscriber subscriber = new KafkaSpansSubscriber(kafka, props, dropped);

        subscriber.onNext(sampleSpan());

        // send 在专用后台执行器上执行（Critical-1 修复），用 timeout 口径等待送达
        verify(kafka, timeout(3000)).send(eq("nl-observe.otlp.traces"), eq("CUST-001"),
                contains("\"resourceSpans\""));
        assertThat(dropped.count()).isZero();
        subscriber.shutdownSender();
    }

    @Test
    void metricsSubscriber_routesToMetricsTopic() {
        completedSend();
        KafkaMetricsSubscriber subscriber = new KafkaMetricsSubscriber(kafka, props, dropped);

        subscriber.onNext(sampleGaugeDataPoint());

        verify(kafka, timeout(3000)).send(eq("nl-observe.otlp.metrics"), eq("CUST-001"),
                contains("\"resourceMetrics\""));
        assertThat(dropped.count()).isZero();
        subscriber.shutdownSender();
    }

    @Test
    void logsSubscriber_routesToLogsTopic() {
        completedSend();
        KafkaLogsSubscriber subscriber = new KafkaLogsSubscriber(kafka, props, dropped);

        subscriber.onNext(sampleLog());

        verify(kafka, timeout(3000)).send(eq("nl-observe.otlp.logs"), eq("CUST-001"),
                contains("\"resourceLogs\""));
        assertThat(dropped.count()).isZero();
        subscriber.shutdownSender();
    }

    @Test
    void sendSyncThrow_incrementsDropped_andDoesNotPropagate() {
        // 防御路径：后台执行器线程上 send 自身同步抛出（正常情形 KafkaTemplate.doSend 会把
        // producer.send 的同步抛出转换为 failed future，走 whenComplete 计数——见 sendAsyncFailure）
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException(new TimeoutException("Timeout waiting for metadata")));
        KafkaSpansSubscriber subscriber = new KafkaSpansSubscriber(kafka, props, dropped);

        assertThatCode(() -> subscriber.onNext(sampleSpan())).doesNotThrowAnyException();
        await().atMost(Duration.ofSeconds(3)).until(() -> dropped.count() == 1.0);
        subscriber.shutdownSender();
    }

    @Test
    void sendAsyncFailure_incrementsDropped() {
        // 入队成功后的异步失败：whenComplete 异常分支计数（Plan B 主路径）
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker down"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
        KafkaSpansSubscriber subscriber = new KafkaSpansSubscriber(kafka, props, dropped);

        subscriber.onNext(sampleSpan());
        await().atMost(Duration.ofSeconds(3)).until(() -> dropped.count() == 1.0);
        subscriber.shutdownSender();
    }

    @Test
    void onSubscribe_requestsUnbounded_andErrorSurvives() {
        KafkaSpansSubscriber subscriber = new KafkaSpansSubscriber(kafka, props, dropped);
        AtomicLong requested = new AtomicLong(-1);
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                requested.set(n);
            }

            @Override
            public void cancel() {
            }
        });
        assertThat(requested.get()).isEqualTo(Long.MAX_VALUE);
        assertThatCode(() -> subscriber.onError(new IllegalStateException("模拟分发失败")))
                .doesNotThrowAnyException();
        subscriber.onComplete();
    }

    @Test
    void missingCustomerId_sendsWithEmptyKey() {
        completedSend();
        KafkaSpansSubscriber subscriber = new KafkaSpansSubscriber(kafka, props, dropped);

        Span span = sampleSpan();
        // 构造无 nl.customer_id 的 resource
        io.opentelemetry.proto.trace.v1.ResourceSpans rs =
                io.opentelemetry.proto.trace.v1.ResourceSpans.newBuilder()
                        .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
                                .addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder()
                                                .setStringValue("x").build()).build()).build())
                        .addScopeSpans(io.opentelemetry.proto.trace.v1.ScopeSpans.newBuilder()
                                .addSpans(span.getSpan()))
                        .build();
        Span noCustomer = new Span(new Batch<>(Context.root()), Context.root(), null);
        noCustomer.setFrom(1L, "b", 0, rs, rs.getScopeSpans(0), span.getSpan());

        subscriber.onNext(noCustomer);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(kafka, timeout(3000)).send(eq("nl-observe.otlp.traces"), key.capture(), anyString());
        assertThat(key.getValue()).isEmpty();
        subscriber.shutdownSender();
    }

    // ---------- 样例记录构造（与样例 OTLP/JSON 资源一致） ----------

    private void completedSend() {
        // 成功路径不触发丢弃计数：完成 future（无需真实 SendResult 明细）
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private static Span sampleSpan() {
        ExportTraceServiceRequest request = parse("otlp/sample-traces.json",
                ExportTraceServiceRequest.getDefaultInstance());
        Span wrapper = new Span(new Batch<>(Context.root()), Context.root(), null);
        wrapper.setFrom(1L, "b", 0, request.getResourceSpans(0),
                request.getResourceSpans(0).getScopeSpans(0),
                request.getResourceSpans(0).getScopeSpans(0).getSpans(0));
        return wrapper;
    }

    private static MetricDataPoint sampleGaugeDataPoint() {
        ExportMetricsServiceRequest request = parse("otlp/sample-metrics.json",
                ExportMetricsServiceRequest.getDefaultInstance());
        var resourceMetrics = request.getResourceMetrics(0);
        var scopeMetrics = resourceMetrics.getScopeMetrics(0);
        var metric = scopeMetrics.getMetrics(0);
        MetricDataPoint wrapper = new MetricDataPoint(new Batch<>(Context.root()), Context.root(), null);
        Gauge gauge = metric.getGauge();
        wrapper.setFrom(1L, "b", 0, resourceMetrics, scopeMetrics, metric, gauge, 0, gauge.getDataPoints(0));
        return wrapper;
    }

    private static Log sampleLog() {
        ExportLogsServiceRequest request = parse("otlp/sample-logs.json",
                ExportLogsServiceRequest.getDefaultInstance());
        var resourceLogs = request.getResourceLogs(0);
        var scopeLogs = resourceLogs.getScopeLogs(0);
        Log wrapper = new Log(new Batch<>(Context.root()), Context.root(), null);
        wrapper.setFrom(1L, "b", 0, resourceLogs, scopeLogs, scopeLogs.getLogRecords(0));
        return wrapper;
    }

    /** protobuf 4.x：Parser 不再暴露 newBuilderForType，改以 prototype 取 builder。 */
    private static <T extends com.google.protobuf.Message> T parse(String resource, T prototype) {
        try (InputStream in = KafkaSubscribersTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("测试资源 %s 必须存在", resource).isNotNull();
            var builder = prototype.toBuilder();
            JsonFormat.parser().merge(new StringReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
                    builder);
            @SuppressWarnings("unchecked")
            T message = (T) builder.build();
            return message;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

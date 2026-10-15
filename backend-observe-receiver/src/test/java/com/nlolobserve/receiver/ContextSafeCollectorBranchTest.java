package com.nlolobserve.receiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import io.mishmash.opentelemetry.server.collector.Batch;
import io.mishmash.opentelemetry.server.collector.Instrumentation;
import io.mishmash.opentelemetry.server.collector.MetricDataPoint;
import io.mishmash.opentelemetry.server.collector.MetricsSubscriber;
import io.mishmash.opentelemetry.server.collector.Span;
import io.mishmash.opentelemetry.server.collector.SpansSubscriber;
import io.opentelemetry.context.Context;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.Test;

import com.nlolobserve.receiver.collector.ContextSafeMetricsCollector;
import com.nlolobserve.receiver.collector.ContextSafeTracesCollector;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextSafe* 覆写（2 参 publish）的故障分支覆盖（T04 评审 Major：失败分支不得静默）。
 * 用测试子类直接注入三种故障，经真实 Vert.x HTTP 服务器验证入口状态码：
 * 4 参 publish 抛出 → 500；处理超 10s 上限 → 503；响应构建失败 → 503。
 * 注意：超时用例各耗时约 10s（超时语义本身即等待，属设计内代价）。
 */
class ContextSafeCollectorBranchTest {

    // ---------- traces ----------

    @Test
    void tracesPublishFailure_returns500() throws Exception {
        assertTracesStatus(500, "otlp/sample-traces.json",
                ContextSafeCollectorBranchTest::throwingTraces);
    }

    @Test
    void tracesPublishTimeout_returns503() throws Exception {
        assertTracesStatus(503, "otlp/sample-traces.json",
                ContextSafeCollectorBranchTest::slowTraces);
    }

    @Test
    void tracesResponseBuildFailure_returns503() throws Exception {
        assertTracesStatus(503, "otlp/sample-traces.json",
                ContextSafeCollectorBranchTest::failingResponseTraces);
    }

    // ---------- metrics ----------

    @Test
    void metricsPublishFailure_returns500() throws Exception {
        assertMetricsStatus(500, "otlp/sample-metrics.json",
                ContextSafeCollectorBranchTest::throwingMetrics);
    }

    @Test
    void metricsPublishTimeout_returns503() throws Exception {
        assertMetricsStatus(503, "otlp/sample-metrics.json",
                ContextSafeCollectorBranchTest::slowMetrics);
    }

    @Test
    void metricsResponseBuildFailure_returns503() throws Exception {
        assertMetricsStatus(503, "otlp/sample-metrics.json",
                ContextSafeCollectorBranchTest::failingResponseMetrics);
    }

    // ---------- 脚手架 ----------

    private static void assertTracesStatus(int expected, String resource,
                                           BiFunction<Vertx, Instrumentation, ContextSafeTracesCollector> factory)
            throws Exception {
        Vertx vertx = Vertx.vertx();
        ContextSafeTracesCollector collector = factory.apply(vertx, new Instrumentation());
        HttpServer server = null;
        try {
            collector.subscribe(noopSpansSubscriber());
            Router router = Router.router(vertx);
            collector.bind(router);
            server = vertx.createHttpServer().requestHandler(router);
            await(server.listen(freePort()));
            int actual = post(server.actualPort(), "/v1/traces", readResource(resource));
            assertThat(actual).as("OTLP/HTTP /v1/traces 入口状态码").isEqualTo(expected);
        } finally {
            collector.close();
            if (server != null) {
                server.close();
            }
            vertx.close();
        }
    }

    private static void assertMetricsStatus(int expected, String resource,
                                            BiFunction<Vertx, Instrumentation, ContextSafeMetricsCollector> factory)
            throws Exception {
        Vertx vertx = Vertx.vertx();
        ContextSafeMetricsCollector collector = factory.apply(vertx, new Instrumentation());
        HttpServer server = null;
        try {
            collector.subscribe(noopMetricsSubscriber());
            Router router = Router.router(vertx);
            collector.bind(router);
            server = vertx.createHttpServer().requestHandler(router);
            await(server.listen(freePort()));
            int actual = post(server.actualPort(), "/v1/metrics", readResource(resource));
            assertThat(actual).as("OTLP/HTTP /v1/metrics 入口状态码").isEqualTo(expected);
        } finally {
            collector.close();
            if (server != null) {
                server.close();
            }
            vertx.close();
        }
    }

    // ---------- 故障注入子类（4 参 publish / getBatchResponse 为 protected/public，可覆写） ----------

    private static ContextSafeTracesCollector throwingTraces(Vertx vertx, Instrumentation instrumentation) {
        return new ContextSafeTracesCollector(vertx, instrumentation) {
            @Override
            protected Batch<Span> publish(ExportTraceServiceRequest request, String transport,
                                          String encoding, Context otelContext) {
                throw new IllegalStateException("测试注入：发布失败");
            }
        };
    }

    private static ContextSafeTracesCollector slowTraces(Vertx vertx, Instrumentation instrumentation) {
        return new ContextSafeTracesCollector(vertx, instrumentation) {
            @Override
            protected Batch<Span> publish(ExportTraceServiceRequest request, String transport,
                                          String encoding, Context otelContext) {
                try {
                    // 超过库超时上限（DEFAULT_TIMEOUT_SEC=10）：onTimeout 分支应触发
                    Thread.sleep(11_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.publish(request, transport, encoding, otelContext);
            }
        };
    }

    private static ContextSafeTracesCollector failingResponseTraces(Vertx vertx, Instrumentation instrumentation) {
        return new ContextSafeTracesCollector(vertx, instrumentation) {
            @Override
            public ExportTraceServiceResponse getBatchResponse(ExportTraceServiceRequest request,
                                                               Batch<Span> completedBatch,
                                                               String transport, String encoding) {
                throw new IllegalStateException("测试注入：响应构建失败");
            }
        };
    }

    private static ContextSafeMetricsCollector throwingMetrics(Vertx vertx, Instrumentation instrumentation) {
        return new ContextSafeMetricsCollector(vertx, instrumentation) {
            @Override
            protected Batch<MetricDataPoint> publish(ExportMetricsServiceRequest request, String transport,
                                                     String encoding, Context otelContext) {
                throw new IllegalStateException("测试注入：发布失败");
            }
        };
    }

    private static ContextSafeMetricsCollector slowMetrics(Vertx vertx, Instrumentation instrumentation) {
        return new ContextSafeMetricsCollector(vertx, instrumentation) {
            @Override
            protected Batch<MetricDataPoint> publish(ExportMetricsServiceRequest request, String transport,
                                                     String encoding, Context otelContext) {
                try {
                    Thread.sleep(11_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.publish(request, transport, encoding, otelContext);
            }
        };
    }

    private static ContextSafeMetricsCollector failingResponseMetrics(Vertx vertx, Instrumentation instrumentation) {
        return new ContextSafeMetricsCollector(vertx, instrumentation) {
            @Override
            public ExportMetricsServiceResponse getBatchResponse(ExportMetricsServiceRequest request,
                                                                 Batch<MetricDataPoint> completedBatch,
                                                                 String transport, String encoding) {
                throw new IllegalStateException("测试注入：响应构建失败");
            }
        };
    }

    // ---------- 辅助 ----------

    private static SpansSubscriber noopSpansSubscriber() {
        return new SpansSubscriber() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Span span) {
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

    private static MetricsSubscriber noopMetricsSubscriber() {
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

    private static void await(io.vertx.core.Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Vert.x 操作失败", e);
        }
    }

    private static int post(int port, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode();
    }

    private static String readResource(String name) {
        try (InputStream in = ContextSafeCollectorBranchTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

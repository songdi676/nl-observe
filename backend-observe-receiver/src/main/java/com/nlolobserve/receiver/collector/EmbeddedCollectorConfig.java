package com.nlolobserve.receiver.collector;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.nlolobserve.receiver.config.OtlpProperties;

import io.mishmash.opentelemetry.server.collector.Instrumentation;
import io.mishmash.opentelemetry.server.collector.MetricsCollector;
import io.mishmash.opentelemetry.server.collector.TracesCollector;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 内嵌 collector-embedded 1.2.1 生命周期（详设 §1.2，T04）。
 * start：Vert.x HTTP 服务器绑定 observe.otlp.port（默认 4318），TracesCollector/MetricsCollector
 * 经 Router 暴露 POST /v1/traces、/v1/metrics（OTLP/JSON 与 protobuf 均可，T01 poc-notes §1）；
 * 仅启用 HTTP，不绑定 GrpcServer（gRPC 4317 不启用）。
 * stop：关闭 collector（含其 ForkJoinPool）、HTTP 服务器与 Vert.x（事件循环/worker 线程），
 * 应用关闭无端口泄漏。
 * collector-embedded 无内建 exporter（T01 poc-notes §1）：下游写入点是注册的 subscriber，
 * T04 用占位订阅者打通接收，T05 替换为 Kafka 三 topic 写入。
 */
@Component
public class EmbeddedCollectorConfig implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedCollectorConfig.class);

    /** Vert.x 启停操作等待上限（秒）：listen/close 均为本地操作，超时即环境异常 */
    private static final long OPERATION_TIMEOUT_SEC = 10;

    private final OtlpProperties otlpProperties;

    private Vertx vertx;
    private HttpServer httpServer;
    private TracesCollector tracesCollector;
    private MetricsCollector metricsCollector;
    private volatile boolean running;

    public EmbeddedCollectorConfig(OtlpProperties otlpProperties) {
        this.otlpProperties = otlpProperties;
    }

    @Override
    public void start() {
        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        Instrumentation instrumentation = new Instrumentation();
        // 仅 HTTP：ServiceMethod 只用于 gRPC 绑定路径，此处不启用 gRPC 4317（详设 §1.2）。
        // ContextSafe* 子类适配 1.2.1 的 HTTP 通路缺陷（见类注释），绑定路由与库行为一致
        tracesCollector = new ContextSafeTracesCollector(vertx, instrumentation);
        metricsCollector = new ContextSafeMetricsCollector(vertx, instrumentation);
        tracesCollector.subscribe(new PlaceholderSpansSubscriber());
        metricsCollector.subscribe(new PlaceholderMetricsSubscriber());
        tracesCollector.bind(router);
        metricsCollector.bind(router);
        httpServer = vertx.createHttpServer().requestHandler(router);
        try {
            await(httpServer.listen(otlpProperties.getPort()), "OTLP/HTTP listen");
        } catch (IllegalStateException e) {
            // 监听失败（如端口被占用）：释放已创建的 Vert.x/collector，避免线程泄漏
            releaseResources();
            throw e;
        }
        running = true;
        LOG.info("内嵌 collector 已启动：OTLP/HTTP 端口 {}（gRPC 未启用）", otlpProperties.getPort());
    }

    @Override
    public void stop() {
        running = false;
        releaseResources();
        LOG.info("内嵌 collector 已停止：端口 {} 释放", otlpProperties.getPort());
    }

    /** 释放 collector 与 Vert.x 资源（stop 与 start 失败回滚共用） */
    private void releaseResources() {
        if (tracesCollector != null) {
            tracesCollector.close();
        }
        if (metricsCollector != null) {
            metricsCollector.close();
        }
        if (httpServer != null) {
            await(httpServer.close(), "HTTP server close");
        }
        if (vertx != null) {
            await(vertx.close(), "Vert.x close");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void await(Future<?> future, String operation) {
        try {
            future.toCompletionStage().toCompletableFuture().get(OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " 被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(operation + " 失败", e);
        }
    }
}

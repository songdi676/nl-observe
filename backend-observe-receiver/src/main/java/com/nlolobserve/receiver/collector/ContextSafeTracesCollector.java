package com.nlolobserve.receiver.collector;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.mishmash.opentelemetry.server.collector.Instrumentation;
import io.mishmash.opentelemetry.server.collector.TracesCollector;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * collector-embedded 1.2.1 HTTP 通路缺陷适配（T04 实测）。
 * 库的 publish(RoutingContext, REQ) 用 CompletableFuture.supplyAsync(supplier)（JDK common pool）
 * 执行 4 参 publish，而后者无条件解引用 Vertx.currentContext()（AbstractCollector.java:495）——
 * common pool 线程上为 null，每个 OTLP/HTTP 请求都 500。
 * 本类覆写该 2 参方法：处理体改派发到 Vert.x worker 上下文（executeBlocking，currentContext 非空），
 * 成功/超时/失败的状态码语义（200/503/500）与库实现一致（错误事件用同一 Instrumentation 实例记录）。
 * T06 追加 checkBody 覆写：请求体上限改由可配置过滤器层执行（库内 10MB 硬编码不可配，
 * 见 {@link HttpBodyGuards}）——上游即使修复 HTTP 通路缺陷，本适配仍需保留以支持可配上限。
 */
public class ContextSafeTracesCollector extends TracesCollector {

    private static final Logger LOG = LoggerFactory.getLogger(ContextSafeTracesCollector.class);

    private final Instrumentation instrumentation;
    private final Executor vertxWorker;

    public ContextSafeTracesCollector(Vertx vertx, Instrumentation instrumentation) {
        // 仅 HTTP：gRPC ServiceMethod 不使用（详设 §1.2 不启用 4317）
        super(null, instrumentation);
        this.instrumentation = instrumentation;
        this.vertxWorker = command -> vertx.executeBlocking(() -> {
            command.run();
            return null;
        });
    }

    @Override
    protected void checkBody(RoutingContext ctx) {
        // T06：请求体上限由 OtlpSecurityFilter 在 Router 前按可配置 observe.security.max-request-bytes
        // 强制执行（413/41301）；此处跳过库硬编码 10MB 检查，保留缺体/Expect 语义（见 HttpBodyGuards）
        HttpBodyGuards.checkBodyOrNext(ctx);
    }

    @Override
    protected void publish(RoutingContext ctx, ExportTraceServiceRequest request) {
        CompletableFuture
            .supplyAsync(() -> publish(request, "http", httpEncoding(ctx), getOtelContext(ctx)), vertxWorker)
            .orTimeout(DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .whenComplete((batch, err) -> {
                if (err == null) {
                    try {
                        setOtelResponse(ctx, getBatchResponse(request, batch, "http", httpEncoding(ctx)));
                    } catch (Exception e) {
                        LOG.error("Collector failed to build http response for /v1/traces", e);
                        setErrorCode(ctx, HTTP_SERVICE_UNAVAILABLE);
                        instrumentation.addErrorEvent(getOtelContext(ctx),
                                new RuntimeException("Collector failed to build http response", e));
                    }
                } else if (err instanceof TimeoutException) {
                    LOG.error("Collector failed to publish trace records within allowed time", err);
                    setErrorCode(ctx, HTTP_SERVICE_UNAVAILABLE);
                    instrumentation.addErrorEvent(getOtelContext(ctx),
                            new RuntimeException("Collector failed to publish records within allowed time", err));
                } else {
                    LOG.error("Collector failed to publish trace records", err);
                    setErrorCode(ctx, HTTP_INTERNAL_ERROR);
                    instrumentation.addErrorEvent(getOtelContext(ctx),
                            new RuntimeException("Collector failed to publish records", err));
                }
                ctx.next();
            });
    }
}

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

/**
 * collector-embedded 1.2.1 HTTP 通路缺陷适配（T04 实测）。
 * 库的 publish(RoutingContext, REQ) 用 CompletableFuture.supplyAsync(supplier)（JDK common pool）
 * 执行 4 参 publish，而后者无条件解引用 Vertx.currentContext()（AbstractCollector.java:495）——
 * common pool 线程上为 null，每个 OTLP/HTTP 请求都 500。
 * 本类覆写该 2 参方法：处理体改派发到 Vert.x worker 上下文（executeBlocking，currentContext 非空），
 * 成功/超时/失败的状态码语义（200/503/500）与库实现一致（错误事件用同一 Instrumentation 实例记录）。
 * 上游若修复（>1.2.1）可删除本类。
 */
public class ContextSafeTracesCollector extends TracesCollector {

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
    protected void publish(RoutingContext ctx, ExportTraceServiceRequest request) {
        CompletableFuture
            .supplyAsync(() -> publish(request, "http", httpEncoding(ctx), getOtelContext(ctx)), vertxWorker)
            .orTimeout(DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .whenComplete((batch, err) -> {
                if (err == null) {
                    try {
                        setOtelResponse(ctx, getBatchResponse(request, batch, "http", httpEncoding(ctx)));
                    } catch (Exception e) {
                        setErrorCode(ctx, HTTP_SERVICE_UNAVAILABLE);
                        instrumentation.addErrorEvent(getOtelContext(ctx),
                                new RuntimeException("Collector failed to build http response", e));
                    }
                } else if (err instanceof TimeoutException) {
                    setErrorCode(ctx, HTTP_SERVICE_UNAVAILABLE);
                    instrumentation.addErrorEvent(getOtelContext(ctx),
                            new RuntimeException("Collector failed to publish records within allowed time", err));
                } else {
                    setErrorCode(ctx, HTTP_INTERNAL_ERROR);
                    instrumentation.addErrorEvent(getOtelContext(ctx),
                            new RuntimeException("Collector failed to publish records", err));
                }
                ctx.next();
            });
    }
}

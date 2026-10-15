package com.nlolobserve.receiver.collector;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.mishmash.opentelemetry.server.collector.Instrumentation;
import io.mishmash.opentelemetry.server.collector.MetricsCollector;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

/**
 * collector-embedded 1.2.1 HTTP 通路缺陷适配——metrics 信号（说明同 {@link ContextSafeTracesCollector}）。
 */
public class ContextSafeMetricsCollector extends MetricsCollector {

    private final Instrumentation instrumentation;
    private final Executor vertxWorker;

    public ContextSafeMetricsCollector(Vertx vertx, Instrumentation instrumentation) {
        // 仅 HTTP：gRPC ServiceMethod 不使用（详设 §1.2 不启用 4317）
        super(null, instrumentation);
        this.instrumentation = instrumentation;
        this.vertxWorker = command -> vertx.executeBlocking(() -> {
            command.run();
            return null;
        });
    }

    @Override
    protected void publish(RoutingContext ctx, ExportMetricsServiceRequest request) {
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

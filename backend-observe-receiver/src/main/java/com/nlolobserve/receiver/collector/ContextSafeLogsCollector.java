package com.nlolobserve.receiver.collector;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.mishmash.opentelemetry.server.collector.Instrumentation;
import io.mishmash.opentelemetry.server.collector.LogsCollector;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * collector-embedded 1.2.1 HTTP 通路缺陷适配——logs 信号（说明同 {@link ContextSafeTracesCollector}）。
 * T05 启用 /v1/logs（OTLP receiver 仅 HTTP，详设 §1.2）。
 */
public class ContextSafeLogsCollector extends LogsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(ContextSafeLogsCollector.class);

    private final Instrumentation instrumentation;
    private final Executor vertxWorker;

    public ContextSafeLogsCollector(Vertx vertx, Instrumentation instrumentation) {
        // 仅 HTTP：gRPC ServiceMethod 不使用（详设 §1.2 不启用 4317）
        super(null, instrumentation);
        this.instrumentation = instrumentation;
        this.vertxWorker = command -> vertx.executeBlocking(() -> {
            command.run();
            return null;
        });
    }

    @Override
    protected void publish(RoutingContext ctx, ExportLogsServiceRequest request) {
        CompletableFuture
            .supplyAsync(() -> publish(request, "http", httpEncoding(ctx), getOtelContext(ctx)), vertxWorker)
            .orTimeout(DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .whenComplete((batch, err) -> {
                if (err == null) {
                    try {
                        setOtelResponse(ctx, getBatchResponse(request, batch, "http", httpEncoding(ctx)));
                    } catch (Exception e) {
                        LOG.error("Collector failed to build http response for /v1/logs", e);
                        setErrorCode(ctx, HTTP_SERVICE_UNAVAILABLE);
                        instrumentation.addErrorEvent(getOtelContext(ctx),
                                new RuntimeException("Collector failed to build http response", e));
                    }
                } else if (err instanceof TimeoutException) {
                    LOG.error("Collector failed to publish log records within allowed time", err);
                    setErrorCode(ctx, HTTP_SERVICE_UNAVAILABLE);
                    instrumentation.addErrorEvent(getOtelContext(ctx),
                            new RuntimeException("Collector failed to publish records within allowed time", err));
                } else {
                    LOG.error("Collector failed to publish log records", err);
                    setErrorCode(ctx, HTTP_INTERNAL_ERROR);
                    instrumentation.addErrorEvent(getOtelContext(ctx),
                            new RuntimeException("Collector failed to publish records", err));
                }
                ctx.next();
            });
    }
}

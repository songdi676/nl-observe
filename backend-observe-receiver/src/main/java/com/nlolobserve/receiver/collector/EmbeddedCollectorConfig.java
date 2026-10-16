package com.nlolobserve.receiver.collector;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.receiver.config.OtlpProperties;
import com.nlolobserve.receiver.config.SecurityFilterProperties;
import com.nlolobserve.receiver.filter.CidrMatcher;
import com.nlolobserve.receiver.filter.OtlpSecurityFilter;
import com.nlolobserve.receiver.filter.SlidingWindowRateLimiter;
import com.nlolobserve.receiver.metrics.RejectedCounter;

import io.mishmash.opentelemetry.server.collector.Instrumentation;
import io.mishmash.opentelemetry.server.collector.LogsCollector;
import io.mishmash.opentelemetry.server.collector.LogsSubscriber;
import io.mishmash.opentelemetry.server.collector.MetricsCollector;
import io.mishmash.opentelemetry.server.collector.MetricsSubscriber;
import io.mishmash.opentelemetry.server.collector.SpansSubscriber;
import io.mishmash.opentelemetry.server.collector.TracesCollector;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 内嵌 collector-embedded 1.2.1 生命周期（详设 §1.2，T04）。
 * start：Vert.x HTTP 服务器绑定 observe.otlp.port（默认 4318），请求先经
 * T06 安全过滤器链 OtlpSecurityFilter（IP 白名单 403 → 限流 429 → 请求体上限 413，
 * observe.security.*）再进入 Router；Traces/Metrics/Logs Collector 经 Router 暴露
 * POST /v1/traces、/v1/metrics、/v1/logs（OTLP/JSON 与 protobuf 均可，T01 poc-notes §1）；
 * 仅启用 HTTP，不绑定 GrpcServer（gRPC 4317 不启用）。
 * stop：关闭 collector（含其 ForkJoinPool）、HTTP 服务器与 Vert.x（事件循环/worker 线程），
 * 应用关闭无端口泄漏。
 * collector-embedded 无内建 exporter（T01 poc-notes §1）：下游写入点是注册的 subscriber，
 * T05 以 Kafka 三 topic 写入订阅者（KafkaSpans/Metrics/LogsSubscriber）按信号路由。
 */
@Component
public class EmbeddedCollectorConfig implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedCollectorConfig.class);

    /** Vert.x 启停操作等待上限（秒）：listen/close 均为本地操作，超时即环境异常 */
    private static final long OPERATION_TIMEOUT_SEC = 10;

    /** 连接 idle 超时（秒，T06 评审 Minor：slow-loris 对策）——合法 CL 后缓慢滴写 body 会
     *  无限阻塞库的 VertxInputStream（worker 线程），idle 超时断连收敛 worker 池占用 */
    private static final int IDLE_TIMEOUT_SEC = 30;

    /** 限流窗口表清扫周期（毫秒，评审 F2：移除全过期 key，防内存单调增长） */
    private static final long EVICT_SWEEP_MILLIS = 600_000L;

    private final OtlpProperties otlpProperties;
    private final SecurityFilterProperties securityProperties;
    private final SpansSubscriber spansSubscriber;
    private final MetricsSubscriber metricsSubscriber;
    private final LogsSubscriber logsSubscriber;
    private final RejectedCounter rejectedCounter;
    private final ObjectMapper objectMapper;
    /** 白名单构造期解析（评审 Nit：与数值参数同点 fail-fast——配错时 Bean 构造即失败，
     *  不留到 start() 已创建 Vert.x/collector 之后才靠关闭钩子回收） */
    private final CidrMatcher cidrMatcher;

    private Vertx vertx;
    private HttpServer httpServer;
    private TracesCollector tracesCollector;
    private MetricsCollector metricsCollector;
    private LogsCollector logsCollector;
    private SlidingWindowRateLimiter rateLimiter;
    private long evictTimerId = -1;
    private volatile boolean running;

    /**
     * subscriber 经构造注入（T05 为 Kafka 三 topic 写入订阅者 @Component）。
     * T06 追加安全过滤器依赖（observe.security.* 属性、拒绝计数、统一响应序列化器）。
     * 测试：@MockitoBean 替换订阅者验证端到端投递（OtlpSubscriberDeliveryTest）；
     * ContextSafe* 覆写的失败分支由测试子类直接注入故障覆盖（ContextSafeCollectorBranchTest）。
     */
    public EmbeddedCollectorConfig(OtlpProperties otlpProperties,
                                   SecurityFilterProperties securityProperties,
                                   SpansSubscriber spansSubscriber,
                                   MetricsSubscriber metricsSubscriber,
                                   LogsSubscriber logsSubscriber,
                                   RejectedCounter rejectedCounter,
                                   ObjectMapper objectMapper) {
        this.otlpProperties = otlpProperties;
        this.securityProperties = securityProperties;
        this.spansSubscriber = spansSubscriber;
        this.metricsSubscriber = metricsSubscriber;
        this.logsSubscriber = logsSubscriber;
        this.rejectedCounter = rejectedCounter;
        this.objectMapper = objectMapper;
        securityProperties.validate();
        this.cidrMatcher = CidrMatcher.of(securityProperties.getAllowedCidrs());
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
        logsCollector = new ContextSafeLogsCollector(vertx, instrumentation);
        tracesCollector.subscribe(spansSubscriber);
        metricsCollector.subscribe(metricsSubscriber);
        logsCollector.subscribe(logsSubscriber);
        tracesCollector.bind(router);
        metricsCollector.bind(router);
        logsCollector.bind(router);
        // T06：入口安全过滤器（白名单 403 → 限流 429 → 体积 413）挂在 Router 之前；
        // 放行后由本过滤器以 router 为下游续链（router 本身即 Handler<HttpServerRequest>）
        rateLimiter = new SlidingWindowRateLimiter(securityProperties.getRateLimitPerMin(),
                System::currentTimeMillis);
        OtlpSecurityFilter securityFilter = new OtlpSecurityFilter(cidrMatcher,
                rateLimiter,
                securityProperties.getMaxRequestBytes(),
                rejectedCounter,
                objectMapper,
                router);
        // idle 超时（评审 Minor：slow-loris 对策）：合法 CL 后缓慢滴写 body 的客户端会无限
        // 阻塞库 parseHttpRequest 的 VertxInputStream.take()（worker 池 20 线程可被耗尽），
        // idle 超时断连收敛该向量（残余见 OtlpSecurityFilter javadoc）
        httpServer = vertx.createHttpServer(
                new HttpServerOptions().setIdleTimeout(IDLE_TIMEOUT_SEC)) // vertx 5：单位秒
                .requestHandler(securityFilter);
        // 限流窗口表低频清扫（评审 F2）：移除全过期 key，防长期运行内存单调增长
        evictTimerId = vertx.setPeriodic(EVICT_SWEEP_MILLIS, id -> rateLimiter.evictIdle());
        try {
            await(httpServer.listen(otlpProperties.getPort()), "OTLP/HTTP listen");
        } catch (IllegalStateException e) {
            // 监听失败（如端口被占用）：释放已创建的 Vert.x/collector，避免线程泄漏
            releaseResources();
            throw e;
        }
        running = true;
        // 生效安全配置随启动日志留痕（评审 F8：默认 10/8 偏宽，部署核对须可见实际白名单/阈值）
        LOG.info("内嵌 collector 已启动：OTLP/HTTP 端口 {}（gRPC 未启用）白名单={} rate-limit-per-min={} max-request-bytes={}",
                otlpProperties.getPort(), securityProperties.getAllowedCidrs(),
                securityProperties.getRateLimitPerMin(), securityProperties.getMaxRequestBytes());
    }

    @Override
    public void stop() {
        running = false;
        releaseResources();
        LOG.info("内嵌 collector 已停止：端口 {} 释放", otlpProperties.getPort());
    }

    /** 释放 collector 与 Vert.x 资源（stop 与 start 失败回滚共用） */
    private void releaseResources() {
        if (vertx != null && evictTimerId >= 0) {
            vertx.cancelTimer(evictTimerId);
            evictTimerId = -1;
        }
        if (tracesCollector != null) {
            tracesCollector.close();
        }
        if (metricsCollector != null) {
            metricsCollector.close();
        }
        if (logsCollector != null) {
            logsCollector.close();
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

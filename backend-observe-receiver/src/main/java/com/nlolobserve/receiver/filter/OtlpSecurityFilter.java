package com.nlolobserve.receiver.filter;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.web.ApiResult;
import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.receiver.metrics.RejectedCounter;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.internal.http.HttpServerRequestInternal;
import io.vertx.core.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OTLP 入口（内嵌 collector 的 Vert.x HTTP 服务，/v1/traces、/v1/metrics、/v1/logs）
 * 安全过滤器链（T06，详设 §1.2/§4.2，质疑者2 R1 入口防伪造/DoS）——挂载在 Vert.x Router 之前
 * （见 EmbeddedCollectorConfig），拒绝即不再进入 collector：
 * <ol>
 *   <li>IP 白名单：按<b>直连口径</b>（TCP remoteAddress）做 CIDR 匹配，不读取 X-Forwarded-For
 *       等转发头（防伪造）——部署在可信代理后需由代理层先行鉴权，或另行评审放开信任代理。</li>
 *   <li>限流：按客户端 IP 滑动窗口（rate-limit-per-min），超限 429。</li>
 *   <li>请求体上限：Content-Length 超限直接 413（不读体）；CL 存在且达标（且无 Transfer-Encoding）时
 *       <b>不预读请求体</b>直通下游（预读会把请求标记为"已读完"，库 checkContentType 的 pause() 抛
 *       "Request has already been read" → 500），由 collector 库自身通路读体；
 *       CL 缺省（chunked）或 CL+TE 并存（RFC 7230 §3.3.3：TE 优先、CL 被忽略——评审 F1 防御线，
 *       钉定 vertx/netty 版本下该形态在 codec 层即断连，升级后不可依赖）时经 bodyHandler
 *       完整缓冲做累计字节判定，超 max-request-bytes 同样 413，放行经 {@link ChunkedRequestReplay}
 *       重放包装交下游；非 internal 请求（理论不可达）fail-closed 拒绝，不放行无体积兜底的通路（评审 F4）；
 *       残余暴露：白名单内+限流内的 chunked 超大体会被完整缓冲后才拒绝
 *       （与库自身 VertxInputStream 无界队列暴露同级，攻击面已被前两级收敛；
 *       slow-loris 滴写由 EmbeddedCollectorConfig 的连接 idle 超时兜底）。</li>
 * </ol>
 * 拒绝响应体为统一 {code,message,data}（40301/41301/42901，详设 §4.1），并计 nl.receiver.rejected。
 * 检查顺序即防御顺序：白名单 → 限流 → 体积（白名单外来源不创建限流窗口）。
 */
public final class OtlpSecurityFilter implements Handler<HttpServerRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(OtlpSecurityFilter.class);

    private final CidrMatcher cidrMatcher;
    private final SlidingWindowRateLimiter rateLimiter;
    private final long maxRequestBytes;
    private final RejectedCounter rejectedCounter;
    private final ObjectMapper objectMapper;
    private final Handler<HttpServerRequest> downstream;

    /**
     * @param cidrMatcher 白名单（observe.security.allowed-cidrs）
     * @param rateLimiter 限流器（observe.security.rate-limit-per-min）
     * @param maxRequestBytes 请求体上限（observe.security.max-request-bytes）
     * @param rejectedCounter 拒绝计数
     * @param objectMapper 统一响应 JSON 序列化
     * @param downstream 放行后的下游（collector Router）
     */
    public OtlpSecurityFilter(CidrMatcher cidrMatcher,
                              SlidingWindowRateLimiter rateLimiter,
                              long maxRequestBytes,
                              RejectedCounter rejectedCounter,
                              ObjectMapper objectMapper,
                              Handler<HttpServerRequest> downstream) {
        this.cidrMatcher = cidrMatcher;
        this.rateLimiter = rateLimiter;
        this.maxRequestBytes = maxRequestBytes;
        this.rejectedCounter = rejectedCounter;
        this.objectMapper = objectMapper;
        this.downstream = downstream;
    }

    @Override
    public void handle(HttpServerRequest request) {
        InetAddress client = clientAddress(request);
        if (client == null || !cidrMatcher.isAllowed(client)) {
            LOG.warn("OTLP 入口白名单拒绝: remote={}", describe(request.remoteAddress()));
            reject(request, ErrorCode.IP_FORBIDDEN, RejectedCounter.REASON_IP);
            return;
        }
        String clientKey = client.getHostAddress();
        if (!rateLimiter.tryAcquire(clientKey)) {
            LOG.warn("OTLP 入口限流拒绝: remote={} rate-limit-per-min 窗口已满", clientKey);
            reject(request, ErrorCode.RATE_LIMITED, RejectedCounter.REASON_RATE);
            return;
        }
        // RFC 7230 §3.3.3：CL 与 Transfer-Encoding 并存时 TE 优先、CL 被忽略——
        // 该形态一律按"无有效 CL"走累计判定通路（评审 F1：钉定 vertx/netty 版本下 codec 层
        // 直接断连不可利用，但升级后解析器行为可能翻转，不得依赖传输层兜底）
        boolean hasTransferEncoding = request.headers().contains(HttpHeaders.TRANSFER_ENCODING);
        long contentLength = hasTransferEncoding ? -1 : ContentLengths.parse(request);
        if (contentLength > maxRequestBytes) {
            // CL 预检超限：不读体直接 413（防超大 CL 请求占读带宽）
            LOG.warn("OTLP 入口请求体超限拒绝: remote={} content-length={} > max={}",
                    clientKey, contentLength, maxRequestBytes);
            reject(request, ErrorCode.PAYLOAD_TOO_LARGE, RejectedCounter.REASON_SIZE);
            return;
        }
        if (contentLength >= 0) {
            // CL 存在且达标（无 TE）：不得预读请求体——预读会把请求标记为"已读完"，
            // collector 库 checkContentType 的 request.pause() 将抛
            // "Request has already been read" → 500。直通下游由库自身通路读体（与 T06 前一致）。
            downstream.handle(request);
            return;
        }
        // CL 缺省（chunked）或 CL+TE 并存：bodyHandler 完整缓冲做累计字节判定（防上限绕过）。
        // 放行时用重放包装交下游（原请求已"读完"，库 pause() 会抛异常，见 ChunkedRequestReplay）；
        // 残余暴露：白名单内+限流内的 chunked 超大体会被完整缓冲后才拒绝
        // （与库自身 VertxInputStream 无界队列暴露同级，攻击面已被前两级收敛）。
        if (!(request instanceof HttpServerRequestInternal internal)) {
            // 非 inet 内部请求（钉定 vertx 实现下理论不可达）：fail-closed 拒绝（评审 F4）——
            // 直通会把无体积兜底的通路放行（库 checkBody 体积判定已被 HttpBodyGuards 按可配口径取代），
            // 与本过滤器全链 fail-closed 口径一致地拒绝并留痕
            LOG.warn("OTLP 入口 chunked 通路收到非 internal 请求（理论不可达），fail-closed 拒绝: remote={} type={}",
                    clientKey, request.getClass().getName());
            reject(request, ErrorCode.BAD_REQUEST, null);
            return;
        }
        request.bodyHandler(buffer -> {
            if (buffer.length() > maxRequestBytes) {
                LOG.warn("OTLP 入口请求体超限拒绝（chunked 累计）: remote={} body={} > max={}",
                        clientKey, buffer.length(), maxRequestBytes);
                reject(request, ErrorCode.PAYLOAD_TOO_LARGE, RejectedCounter.REASON_SIZE);
                return;
            }
            downstream.handle(new ChunkedRequestReplay(internal, buffer));
        });
        // 连接在读体期间中断：无响应可发，静默结束（限流窗口已计，符合"收到即计数"口径）
        request.exceptionHandler(t -> LOG.debug("OTLP 入口请求在读体阶段异常终止: {}", t.toString()));
    }

    // ---------- 内部 ----------

    /**
     * 直连口径客户端地址：仅 TCP remoteAddress（Vert.x SocketAddress），不采信任何转发头。
     * 无法解析 → null（按白名单外处理，fail-closed）。
     */
    private static InetAddress clientAddress(HttpServerRequest request) {
        SocketAddress remote = request.remoteAddress();
        if (remote == null) {
            return null;
        }
        // Vert.x 5 SocketAddress.hostAddress()：inet 套接字返回 IP 字面量；
        // 域套接字/解析失败返回 null → 按白名单外处理（fail-closed）
        String host = remote.hostAddress();
        if (host == null || host.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String describe(SocketAddress remote) {
        return remote == null ? "unknown" : remote.toString();
    }

    /**
     * @param reason 拒绝原因标签（nl.receiver.rejected）；null 表示不计入该指标
     *               （如 fail-closed 边缘拒绝——非业务拒绝语义，仅 warn 留痕）
     */
    private void reject(HttpServerRequest request, ErrorCode errorCode, String reason) {
        if (reason != null) {
            rejectedCounter.increment(reason);
        }
        HttpServerResponse response = request.response();
        if (response.closed()) {
            return;
        }
        try {
            response
                    .setStatusCode(errorCode.getHttpStatus())
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                    .end(ApiResultJson.toJson(ApiResult.fail(errorCode), objectMapper));
        } catch (RuntimeException e) {
            // 含 IllegalStateException（响应已结束/连接已断开）
            LOG.debug("OTLP 入口拒绝响应写出失败（连接可能已断开）: {}", e.toString());
        }
    }
}

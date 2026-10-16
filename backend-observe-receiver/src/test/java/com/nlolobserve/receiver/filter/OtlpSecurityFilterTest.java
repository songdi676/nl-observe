package com.nlolobserve.receiver.filter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.receiver.metrics.RejectedCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Handler;
import io.vertx.core.internal.http.HttpServerRequestInternal;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T06：OTLP 入口安全过滤器单测（Mockito 模拟 Vert.x 请求/响应）——
 * 检查顺序（白名单→限流→体积）、各拒绝分支的状态码/统一错误码/计数、
 * CL 预检不读体、chunked 累计判定、放行续链。
 */
class OtlpSecurityFilterTest {

    private static final long MAX_BYTES = 1024L;

    private CidrMatcher cidrMatcher;
    private SlidingWindowRateLimiter rateLimiter;
    private RejectedCounter rejectedCounter;
    private SimpleMeterRegistry registry;
    private final AtomicInteger downstreamCalls = new AtomicInteger();
    private Handler<HttpServerRequest> downstream;

    private HttpServerRequest request;
    private HttpServerResponse response;
    private AtomicReference<Handler<Buffer>> bodyHandlerRef;

    @BeforeEach
    void setUp() {
        cidrMatcher = CidrMatcher.of(List.of("127.0.0.1/32"));
        AtomicLong clock = new AtomicLong(0L);
        rateLimiter = new SlidingWindowRateLimiter(100, clock::get);
        registry = new SimpleMeterRegistry();
        rejectedCounter = new RejectedCounter(registry);
        downstream = req -> downstreamCalls.incrementAndGet();

        // 模拟真实 Vert.x 服务端的内部请求类型（HttpServerRequestImpl 实现 HttpServerRequestInternal），
        // 使 chunked 通路的 instanceof 守卫与真实环境一致
        request = mock(HttpServerRequestInternal.class);
        response = mock(HttpServerResponse.class);
        bodyHandlerRef = new AtomicReference<>();
        when(request.response()).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), any(CharSequence.class))).thenReturn(response);
        doAnswer(inv -> {
            bodyHandlerRef.set(inv.getArgument(0));
            return request;
        }).when(request).bodyHandler(any());
    }

    private OtlpSecurityFilter newFilter(long maxBytes) {
        return new OtlpSecurityFilter(cidrMatcher, rateLimiter, maxBytes,
                rejectedCounter, new ObjectMapper(), downstream);
    }

    private void remoteIs(String ipLiteral) {
        SocketAddress remote = SocketAddress.inetSocketAddress(40000, ipLiteral);
        when(request.remoteAddress()).thenReturn(remote);
    }

    private void contentLength(String value) {
        when(request.getHeader(eq(HttpHeaders.CONTENT_LENGTH))).thenReturn(value);
    }

    private JsonNode lastResponseBody() throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(response).end(captor.capture());
        return new ObjectMapper().readTree(captor.getValue());
    }

    @Test
    void nonWhitelistedSource_rejects403_withoutReadingBodyOrRateWindow() throws Exception {
        remoteIs("10.9.9.9");
        contentLength("10");

        newFilter(MAX_BYTES).handle(request);

        verify(response).setStatusCode(403);
        assertThat(lastResponseBody().path("code").asInt()).isEqualTo(40301);
        assertThat(rejectedCounter.count(RejectedCounter.REASON_IP)).isEqualTo(1.0);
        // 白名单外：不创建限流窗口、不读体、不进下游
        assertThat(rateLimiter.keyCount()).isZero();
        verify(request, never()).bodyHandler(any());
        assertThat(downstreamCalls).hasValue(0);
    }

    @Test
    void unresolvableRemoteAddress_rejects403_failClosed() throws Exception {
        SocketAddress remote = mock(SocketAddress.class);
        when(remote.hostAddress()).thenReturn(null);
        when(request.remoteAddress()).thenReturn(remote);

        newFilter(MAX_BYTES).handle(request);

        verify(response).setStatusCode(403);
        assertThat(rejectedCounter.count(RejectedCounter.REASON_IP)).isEqualTo(1.0);
        assertThat(downstreamCalls).hasValue(0);
    }

    @Test
    void overRateLimit_rejects429() throws Exception {
        remoteIs("127.0.0.1");
        contentLength("10");
        SlidingWindowRateLimiter tight = new SlidingWindowRateLimiter(1, () -> 0L);
        OtlpSecurityFilter filter = new OtlpSecurityFilter(cidrMatcher, tight, MAX_BYTES,
                rejectedCounter, new ObjectMapper(), downstream);

        filter.handle(request);
        // CL 存在且达标：不预读请求体，直通下游（与库自身通路读体一致）
        assertThat(downstreamCalls).as("CL 存在且达标时应直通下游").hasValue(1);

        HttpServerRequest second = mock(HttpServerRequest.class);
        HttpServerResponse secondResponse = mock(HttpServerResponse.class);
        when(second.response()).thenReturn(secondResponse);
        when(second.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(40001, "127.0.0.1"));
        when(secondResponse.setStatusCode(anyInt())).thenReturn(secondResponse);
        when(secondResponse.putHeader(any(CharSequence.class), any(CharSequence.class))).thenReturn(secondResponse);

        filter.handle(second);

        verify(secondResponse).setStatusCode(429);
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(secondResponse).end(captor.capture());
        assertThat(new ObjectMapper().readTree(captor.getValue()).path("code").asInt()).isEqualTo(42901);
        assertThat(rejectedCounter.count(RejectedCounter.REASON_RATE)).isEqualTo(1.0);
        assertThat(downstreamCalls).hasValue(1);
    }

    @Test
    void contentLengthOverLimit_rejects413_withoutReadingBody() throws Exception {
        remoteIs("127.0.0.1");
        contentLength(String.valueOf(MAX_BYTES + 1));

        newFilter(MAX_BYTES).handle(request);

        verify(response).setStatusCode(413);
        assertThat(lastResponseBody().path("code").asInt()).isEqualTo(41301);
        assertThat(rejectedCounter.count(RejectedCounter.REASON_SIZE)).isEqualTo(1.0);
        verify(request, never()).bodyHandler(any());
        assertThat(downstreamCalls).hasValue(0);
    }

    @Test
    void chunkedBodyOverLimit_rejects413_afterCumulativeCheck() throws Exception {
        remoteIs("127.0.0.1");
        contentLength(null); // chunked：无 Content-Length

        newFilter(MAX_BYTES).handle(request);
        assertThat(bodyHandlerRef.get()).as("chunked 必须注册 bodyHandler 按累计字节判定").isNotNull();
        byte[] oversized = new byte[(int) MAX_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'a');
        bodyHandlerRef.get().handle(Buffer.buffer(oversized));

        verify(response).setStatusCode(413);
        assertThat(lastResponseBody().path("code").asInt()).isEqualTo(41301);
        assertThat(rejectedCounter.count(RejectedCounter.REASON_SIZE)).isEqualTo(1.0);
        assertThat(downstreamCalls).hasValue(0);
    }

    @Test
    void whitelistedNormalRequest_forwardsToDownstream() throws Exception {
        remoteIs("127.0.0.1");
        contentLength("5");

        newFilter(MAX_BYTES).handle(request);
        // CL 存在且达标：直通下游，不注册 bodyHandler
        assertThat(downstreamCalls).as("白名单内 + 限流内 + CL 达标应直通下游").hasValue(1);
        verify(response, never()).setStatusCode(anyInt());
        assertThat(rejectedCounter.count(RejectedCounter.REASON_IP)).isZero();
        assertThat(rejectedCounter.count(RejectedCounter.REASON_SIZE)).isZero();
        assertThat(rejectedCounter.count(RejectedCounter.REASON_RATE)).isZero();
    }

    @Test
    void malformedContentLength_fallsBackToCumulativeCheck() throws Exception {
        remoteIs("127.0.0.1");
        contentLength("not-a-number"); // CL 非法 → 按缺省处理，走 bodyHandler 累计判定

        newFilter(MAX_BYTES).handle(request);
        assertThat(bodyHandlerRef.get()).as("CL 非法应按 chunked 口径注册 bodyHandler").isNotNull();
        bodyHandlerRef.get().handle(Buffer.buffer("abcde"));
        assertThat(downstreamCalls).hasValue(1);
    }
}

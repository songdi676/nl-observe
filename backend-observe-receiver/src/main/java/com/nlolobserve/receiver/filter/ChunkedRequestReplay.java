package com.nlolobserve.receiver.filter;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.internal.http.HttpServerRequestInternal;
import io.vertx.core.internal.http.HttpServerRequestWrapper;

/**
 * chunked 通路的重放请求包装（T06）。
 *
 * 背景：OtlpSecurityFilter 对无 Content-Length（chunked）请求经 bodyHandler 完整缓冲请求体
 * 以做累计字节上限判定；缓冲完成后原请求处于"已读完"状态，下游 collector 库的
 * checkContentType 会调用 request.pause()，对已读完请求抛
 * IllegalStateException("Request has already been read") → 500。
 *
 * 本包装向下游（collector Router）呈现"请求体已完整接收"的视图，其余全部委托原请求：
 * - pause/resume/fetch/exceptionHandler 空操作（体已读完，流控不再适用）
 * - isEnded()=true、body()=已缓冲体（库 parseHttpRequest 据此走 ByteArrayInputStream 分支）
 * - handler/endHandler 按序重放缓冲体与结束事件（兼容 VertxInputStream 分支，防御性）
 *
 * 注意：基于 vertx-core internal 包（HttpServerRequestWrapper），当前随 collector-embedded
 * 锁定 vertx 5.1.8；升级 vertx 时需回归本类与 chunked 集成用例（OtlpSecurityMaxBodyTest）。
 */
final class ChunkedRequestReplay extends HttpServerRequestWrapper {

    private final Buffer body;

    ChunkedRequestReplay(HttpServerRequestInternal delegate, Buffer body) {
        super(delegate);
        this.body = body;
    }

    @Override
    public HttpServerRequest pause() {
        return this;
    }

    @Override
    public HttpServerRequest resume() {
        return this;
    }

    @Override
    public HttpServerRequest fetch(long amount) {
        return this;
    }

    @Override
    public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
        return this;
    }

    @Override
    public boolean isEnded() {
        return true;
    }

    @Override
    public Future<Buffer> body() {
        return Future.succeededFuture(body);
    }

    @Override
    public Future<Void> end() {
        return Future.succeededFuture();
    }

    @Override
    public long bytesRead() {
        return body.length();
    }

    @Override
    public HttpServerRequest handler(Handler<Buffer> handler) {
        // 防御性重放：体已完整接收，设置数据处理器时立即交付
        handler.handle(body);
        return this;
    }

    @Override
    public HttpServerRequest endHandler(Handler<Void> handler) {
        handler.handle(null);
        return this;
    }
}

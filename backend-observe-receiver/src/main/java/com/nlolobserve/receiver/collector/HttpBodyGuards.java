package com.nlolobserve.receiver.collector;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;

/**
 * collector-embedded 1.2.1 `checkBody` 的可配上限适配（T06）。
 * 库内 checkBody 硬编码 10MB（AbstractCollector.DEFAULT_MAX_BODY_LEN，源码 FIXME 未配置化）：
 * 与 observe.security.max-request-bytes 冲突——配置调高（>10MB）时库会误拒合法请求。
 * 体积上限已由 OtlpSecurityFilter 在 Router 之前按可配值强制执行（413/41301），
 * 本类取代库检查：仅保留缺体（400）与 Expect（100-continue / 417）语义，
 * 不再重复体积判定。库自身的 OTel 计量（addRequestsInProcess 等）不导出、不影响契约，跳过。
 */
final class HttpBodyGuards {

    private HttpBodyGuards() {
    }

    /**
     * 路由级 body 前置检查（不含体积上限，上限在过滤器层）。
     */
    static void checkBodyOrNext(RoutingContext ctx) {
        HttpServerRequest request = ctx.request();

        long contentLength = parseContentLength(request);
        boolean hasTransferEncoding = request.version() == HttpVersion.HTTP_2
                || request.headers().contains(HttpHeaders.TRANSFER_ENCODING);

        if (!hasTransferEncoding && contentLength == -1) {
            ctx.response().setStatusMessage("Request body is missing");
            ctx.fail(400);
            return;
        }

        String expect = request.getHeader(HttpHeaders.EXPECT);
        if (expect != null) {
            if ("100-continue".equalsIgnoreCase(expect)) {
                if (request.version() != HttpVersion.HTTP_1_0) {
                    ctx.response().writeContinue();
                }
            } else {
                ctx.response().setStatusMessage("The server cannot meet the expectation: " + expect);
                ctx.fail(417);
                return;
            }
        }

        ctx.next();
    }

    private static long parseContentLength(HttpServerRequest request) {
        String header = request.getHeader(HttpHeaders.CONTENT_LENGTH);
        if (header == null) {
            return -1;
        }
        try {
            long parsed = Long.parseLong(header.trim());
            return parsed < 0 ? -1 : parsed;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

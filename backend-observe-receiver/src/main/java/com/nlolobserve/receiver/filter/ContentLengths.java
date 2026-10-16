package com.nlolobserve.receiver.filter;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;

/**
 * Content-Length 头解析（T06 评审 Minor：OtlpSecurityFilter 与 collector 侧 HttpBodyGuards
 * 共用单一实现，语义统一：缺省/非法/负值 → -1（按"无有效 CL"处理，走累计判定通路））。
 */
public final class ContentLengths {

    private ContentLengths() {
    }

    /**
     * @param request Vert.x HTTP 请求
     * @return 解析出的 Content-Length；缺省/非法/负值 → -1
     */
    public static long parse(HttpServerRequest request) {
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

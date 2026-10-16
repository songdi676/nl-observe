package com.nlolobserve.storage.parse;

import java.time.Instant;

/**
 * otel_spans 行模型（详设 §1.3 parse/）：OTLP span 展开后的落库行。
 * 去重身份 = 聚簇键 (customer_id, trace_id, span_id, start_time, end_time)（详设 §3）。
 * traceId/spanId 为 common 模型（String）原样值：T05 已还原 SDK 原始 hex 串，
 * 本模型与写入路径不得再做任何 hex/base64 转码。
 */
public record SpanRow(
        String customerId,
        String traceId,
        String spanId,
        String parentSpanId,
        String traceState,
        String name,
        int kind,
        Instant startTime,
        Instant endTime,
        int statusCode,
        String statusMessage,
        String bizCode,
        String serviceName,
        double effectiveDurationMs,
        String resourceJson,
        String scopeJson,
        String attributesJson) {
}

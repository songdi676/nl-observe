package com.nlolobserve.storage.parse;

import java.time.Instant;

/**
 * otel_logs 行模型（详设 §1.3 parse/）：单条 log record 展开后的落库行。
 * 去重身份 = 聚簇键 (customer_id, trace_id, time, row_hash)；
 * row_hash 由 (customer_id, trace_id, span_id, time, severity, body, 属性) 计算，
 * 同记录重复上报折叠、不同记录互不折叠（详设 §3）。
 */
public record LogRow(
        String customerId,
        String traceId,
        String spanId,
        Instant time,
        int severityNumber,
        String severityText,
        String body,
        String resourceJson,
        String attributesJson,
        String rowHash) {
}

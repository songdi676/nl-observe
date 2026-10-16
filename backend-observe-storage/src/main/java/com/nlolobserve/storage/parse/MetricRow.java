package com.nlolobserve.storage.parse;

import java.time.Instant;

/**
 * otel_metrics 行模型（详设 §1.3 parse/）：单个 data point 展开后的落库行。
 * 去重身份 = 聚簇键 (customer_id, metric_name, time, dp_hash)；
 * dp_hash 由 (metric_name, time, value, data point 属性) 计算——不同数据点互不折叠，
 * 同一 data point 重复上报折叠（详设 §3）。
 * metricType 取 OTLP MetricType 枚举值：1=GAUGE，2=COUNTER(SUM)，3=HISTOGRAM。
 */
public record MetricRow(
        String customerId,
        String metricName,
        int metricType,
        Instant time,
        Double value,
        String bizCode,
        String resourceJson,
        String attributesJson,
        String dpHash) {
}

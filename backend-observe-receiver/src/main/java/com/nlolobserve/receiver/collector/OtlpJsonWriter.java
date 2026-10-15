package com.nlolobserve.receiver.collector;

import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nlolobserve.common.constant.AttributeKeys;
import io.mishmash.opentelemetry.server.collector.Log;
import io.mishmash.opentelemetry.server.collector.MetricDataPoint;
import io.mishmash.opentelemetry.server.collector.Span;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;
import io.opentelemetry.proto.trace.v1.Status;

/**
 * 扁平化记录 → OTLP/JSON 信封（详设 §1.2：Kafka 载荷为 OTLP/JSON）。
 * collector-embedded 将 OTLP 包扁平化为逐条记录（resource/scope + 单条 protobuf 记录），
 * 本类将其重建为合法 OTLP/JSON 单记录信封（Export*ServiceRequest 形态），供存储端（T08）按
 * common 模型解析。编码遵循 OTLP/HTTP JSON 规范：camelCase 键、枚举数值、int64/uint64 字符串、
 * trace/span id 十六进制（与 common 模型及样例 JSON 口径一致）。
 * 说明：exemplars 与 resource.entityRefs 不输出（浏览器 SDK 不产生；common 模型亦不消费）。
 */
public final class OtlpJsonWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private OtlpJsonWriter() {
    }

    /** 单 span 的 ExportTraceServiceRequest 信封 JSON。 */
    public static String tracesEnvelope(Span span) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode resourceSpans = root.putArray("resourceSpans").addObject();
        writeResource(resourceSpans, span.getResourceAttributes(), span.getResourceDroppedAttributesCount());
        ObjectNode scopeSpans = resourceSpans.putArray("scopeSpans").addObject();
        writeScope(scopeSpans, span.getScope());
        scopeSpans.putArray("spans").add(spanNode(span.getSpan()));
        return root.toString();
    }

    /** 单 data point 的 ExportMetricsServiceRequest 信封 JSON（按 metric 类型重建聚合壳）。 */
    public static String metricsEnvelope(MetricDataPoint dataPoint) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode resourceMetrics = root.putArray("resourceMetrics").addObject();
        writeResource(resourceMetrics, dataPoint.getResourceAttributes(), dataPoint.getResourceDroppedAttributesCount());
        ObjectNode scopeMetrics = resourceMetrics.putArray("scopeMetrics").addObject();
        writeScope(scopeMetrics, dataPoint.getScope());
        ObjectNode metric = scopeMetrics.putArray("metrics").addObject();
        if (!dataPoint.getName().isEmpty()) {
            metric.put("name", dataPoint.getName());
        }
        if (!dataPoint.getDescription().isEmpty()) {
            metric.put("description", dataPoint.getDescription());
        }
        if (!dataPoint.getUnit().isEmpty()) {
            metric.put("unit", dataPoint.getUnit());
        }
        // gauge/summary 数据点无 aggregationTemporality（OTLP 规范），collector 包装器留空 → null
        Integer temporality = dataPoint.getAggregationTemporality() == null
                ? null : dataPoint.getAggregationTemporality().getNumber();
        switch (dataPoint.getType()) {
            case GAUGE -> metric.putObject("gauge").putArray("dataPoints")
                    .add(numberDataPoint(dataPoint.getGauge()));
            case SUM -> {
                ObjectNode sum = metric.putObject("sum");
                sum.putArray("dataPoints").add(numberDataPoint(dataPoint.getSum()));
                sum.put("aggregationTemporality", temporality);
                sum.put("isMonotonic", dataPoint.isMonotonic());
            }
            case HISTOGRAM -> {
                ObjectNode histogram = metric.putObject("histogram");
                histogram.putArray("dataPoints").add(histogramDataPoint(dataPoint.getHistogram()));
                histogram.put("aggregationTemporality", temporality);
            }
            case EXPONENTIAL_HISTOGRAM -> {
                ObjectNode exponential = metric.putObject("exponentialHistogram");
                exponential.putArray("dataPoints")
                        .add(exponentialHistogramDataPoint(dataPoint.getExponentialHistogram()));
                exponential.put("aggregationTemporality", temporality);
            }
            case SUMMARY -> metric.putObject("summary").putArray("dataPoints")
                    .add(summaryDataPoint(dataPoint.getSummary()));
            default -> throw new IllegalArgumentException("不支持的 metric 类型: " + dataPoint.getType());
        }
        return root.toString();
    }

    /** 单 log record 的 ExportLogsServiceRequest 信封 JSON。 */
    public static String logsEnvelope(Log log) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode resourceLogs = root.putArray("resourceLogs").addObject();
        writeResource(resourceLogs, log.getResourceAttributes(), log.getResourceDroppedAttributesCount());
        ObjectNode scopeLogs = resourceLogs.putArray("scopeLogs").addObject();
        writeScope(scopeLogs, log.getScope());
        scopeLogs.putArray("logRecords").add(logRecordNode(log.getLog()));
        return root.toString();
    }

    /** Kafka 消息 key = resource 属性 nl.customer_id；缺失回退空串（T01 poc 口径）。 */
    public static String customerId(Iterable<KeyValue> resourceAttributes) {
        for (KeyValue kv : resourceAttributes) {
            if (AttributeKeys.NL_CUSTOMER_ID.equals(kv.getKey())) {
                return attributeValueAsString(kv.getValue());
            }
        }
        return "";
    }

    // ---------- 内部：OTLP/JSON 节点构建 ----------

    private static void writeResource(ObjectNode parent, Iterable<KeyValue> attributes, int droppedCount) {
        ObjectNode resource = parent.putObject("resource");
        writeAttributes(resource.putArray("attributes"), attributes);
        if (droppedCount > 0) {
            resource.put("droppedAttributesCount", droppedCount);
        }
    }

    private static void writeScope(ObjectNode parent, InstrumentationScope scope) {
        ObjectNode scopeNode = parent.putObject("scope");
        if (!scope.getName().isEmpty()) {
            scopeNode.put("name", scope.getName());
        }
        if (!scope.getVersion().isEmpty()) {
            scopeNode.put("version", scope.getVersion());
        }
        if (!scope.getAttributesList().isEmpty()) {
            writeAttributes(scopeNode.putArray("attributes"), scope.getAttributesList());
        }
        if (scope.getDroppedAttributesCount() > 0) {
            scopeNode.put("droppedAttributesCount", scope.getDroppedAttributesCount());
        }
    }

    private static ObjectNode spanNode(io.opentelemetry.proto.trace.v1.Span span) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("traceId", hex(span.getTraceId()));
        node.put("spanId", hex(span.getSpanId()));
        if (!span.getParentSpanId().isEmpty()) {
            node.put("parentSpanId", hex(span.getParentSpanId()));
        }
        if (!span.getTraceState().isEmpty()) {
            node.put("traceState", span.getTraceState());
        }
        node.put("name", span.getName());
        node.put("kind", span.getKindValue());
        node.put("startTimeUnixNano", String.valueOf(span.getStartTimeUnixNano()));
        node.put("endTimeUnixNano", String.valueOf(span.getEndTimeUnixNano()));
        writeAttributes(node.putArray("attributes"), span.getAttributesList());
        if (span.getDroppedAttributesCount() > 0) {
            node.put("droppedAttributesCount", span.getDroppedAttributesCount());
        }
        ArrayNode events = node.putArray("events");
        for (io.opentelemetry.proto.trace.v1.Span.Event event : span.getEventsList()) {
            ObjectNode eventNode = events.addObject();
            eventNode.put("timeUnixNano", String.valueOf(event.getTimeUnixNano()));
            eventNode.put("name", event.getName());
            writeAttributes(eventNode.putArray("attributes"), event.getAttributesList());
            if (event.getDroppedAttributesCount() > 0) {
                eventNode.put("droppedAttributesCount", event.getDroppedAttributesCount());
            }
        }
        ArrayNode links = node.putArray("links");
        for (io.opentelemetry.proto.trace.v1.Span.Link link : span.getLinksList()) {
            ObjectNode linkNode = links.addObject();
            linkNode.put("traceId", hex(link.getTraceId()));
            linkNode.put("spanId", hex(link.getSpanId()));
            if (!link.getTraceState().isEmpty()) {
                linkNode.put("traceState", link.getTraceState());
            }
            writeAttributes(linkNode.putArray("attributes"), link.getAttributesList());
            if (link.getDroppedAttributesCount() > 0) {
                linkNode.put("droppedAttributesCount", link.getDroppedAttributesCount());
            }
        }
        Status status = span.getStatus();
        ObjectNode statusNode = node.putObject("status");
        statusNode.put("code", status.getCodeValue());
        if (!status.getMessage().isEmpty()) {
            statusNode.put("message", status.getMessage());
        }
        return node;
    }

    private static ObjectNode logRecordNode(LogRecord record) {
        ObjectNode node = MAPPER.createObjectNode();
        if (record.getTimeUnixNano() != 0L) {
            node.put("timeUnixNano", String.valueOf(record.getTimeUnixNano()));
        }
        if (record.getObservedTimeUnixNano() != 0L) {
            node.put("observedTimeUnixNano", String.valueOf(record.getObservedTimeUnixNano()));
        }
        if (record.getSeverityNumberValue() != 0) {
            node.put("severityNumber", record.getSeverityNumberValue());
        }
        if (!record.getSeverityText().isEmpty()) {
            node.put("severityText", record.getSeverityText());
        }
        if (record.getBody().getValueCase() != AnyValue.ValueCase.VALUE_NOT_SET) {
            node.set("body", anyValueNode(record.getBody()));
        }
        writeAttributes(node.putArray("attributes"), record.getAttributesList());
        if (record.getDroppedAttributesCount() > 0) {
            node.put("droppedAttributesCount", record.getDroppedAttributesCount());
        }
        if (record.getFlags() != 0) {
            node.put("flags", record.getFlags());
        }
        if (!record.getTraceId().isEmpty()) {
            node.put("traceId", hex(record.getTraceId()));
        }
        if (!record.getSpanId().isEmpty()) {
            node.put("spanId", hex(record.getSpanId()));
        }
        return node;
    }

    private static ObjectNode numberDataPoint(NumberDataPoint point) {
        ObjectNode node = MAPPER.createObjectNode();
        writeAttributes(node.putArray("attributes"), point.getAttributesList());
        node.put("startTimeUnixNano", String.valueOf(point.getStartTimeUnixNano()));
        node.put("timeUnixNano", String.valueOf(point.getTimeUnixNano()));
        switch (point.getValueCase()) {
            case AS_DOUBLE -> node.put("asDouble", point.getAsDouble());
            case AS_INT -> node.put("asInt", String.valueOf(point.getAsInt()));
            default -> {
                // VALUE_NOT_SET：无数值，仅保留时间/属性（存储端按无效处理）
            }
        }
        return node;
    }

    private static ObjectNode histogramDataPoint(HistogramDataPoint point) {
        ObjectNode node = MAPPER.createObjectNode();
        writeAttributes(node.putArray("attributes"), point.getAttributesList());
        node.put("startTimeUnixNano", String.valueOf(point.getStartTimeUnixNano()));
        node.put("timeUnixNano", String.valueOf(point.getTimeUnixNano()));
        node.put("count", String.valueOf(point.getCount()));
        if (point.hasSum()) {
            node.put("sum", point.getSum());
        }
        ArrayNode bucketCounts = node.putArray("bucketCounts");
        for (long count : point.getBucketCountsList()) {
            bucketCounts.add(String.valueOf(count));
        }
        ArrayNode explicitBounds = node.putArray("explicitBounds");
        for (double bound : point.getExplicitBoundsList()) {
            explicitBounds.add(bound);
        }
        if (point.hasMin()) {
            node.put("min", point.getMin());
        }
        if (point.hasMax()) {
            node.put("max", point.getMax());
        }
        return node;
    }

    private static ObjectNode exponentialHistogramDataPoint(ExponentialHistogramDataPoint point) {
        ObjectNode node = MAPPER.createObjectNode();
        writeAttributes(node.putArray("attributes"), point.getAttributesList());
        node.put("startTimeUnixNano", String.valueOf(point.getStartTimeUnixNano()));
        node.put("timeUnixNano", String.valueOf(point.getTimeUnixNano()));
        node.put("count", String.valueOf(point.getCount()));
        if (point.hasSum()) {
            node.put("sum", point.getSum());
        }
        node.put("scale", point.getScale());
        node.put("zeroCount", String.valueOf(point.getZeroCount()));
        writeHistogramBuckets(node.putObject("positive"), point.getPositive());
        writeHistogramBuckets(node.putObject("negative"), point.getNegative());
        if (point.hasMin()) {
            node.put("min", point.getMin());
        }
        if (point.hasMax()) {
            node.put("max", point.getMax());
        }
        return node;
    }

    private static void writeHistogramBuckets(ObjectNode node,
                                              ExponentialHistogramDataPoint.Buckets buckets) {
        node.put("offset", buckets.getOffset());
        ArrayNode counts = node.putArray("bucketCounts");
        for (long count : buckets.getBucketCountsList()) {
            counts.add(String.valueOf(count));
        }
    }

    private static ObjectNode summaryDataPoint(SummaryDataPoint point) {
        ObjectNode node = MAPPER.createObjectNode();
        writeAttributes(node.putArray("attributes"), point.getAttributesList());
        node.put("startTimeUnixNano", String.valueOf(point.getStartTimeUnixNano()));
        node.put("timeUnixNano", String.valueOf(point.getTimeUnixNano()));
        node.put("count", String.valueOf(point.getCount()));
        node.put("sum", point.getSum());
        ArrayNode quantiles = node.putArray("quantileValues");
        for (SummaryDataPoint.ValueAtQuantile quantile : point.getQuantileValuesList()) {
            ObjectNode quantileNode = quantiles.addObject();
            quantileNode.put("quantile", quantile.getQuantile());
            quantileNode.put("value", quantile.getValue());
        }
        return node;
    }

    private static void writeAttributes(ArrayNode array, Iterable<KeyValue> attributes) {
        for (KeyValue kv : attributes) {
            ObjectNode node = array.addObject();
            node.put("key", kv.getKey());
            node.set("value", anyValueNode(kv.getValue()));
        }
    }

    private static ObjectNode anyValueNode(AnyValue value) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (value.getValueCase()) {
            case STRING_VALUE -> node.put("stringValue", value.getStringValue());
            case INT_VALUE -> node.put("intValue", String.valueOf(value.getIntValue()));
            case DOUBLE_VALUE -> node.put("doubleValue", value.getDoubleValue());
            case BOOL_VALUE -> node.put("boolValue", value.getBoolValue());
            case BYTES_VALUE -> node.put("bytesValue",
                    Base64.getEncoder().encodeToString(value.getBytesValue().toByteArray()));
            case KVLIST_VALUE -> {
                ArrayNode values = node.putObject("kvlistValue").putArray("values");
                for (KeyValue kv : value.getKvlistValue().getValuesList()) {
                    ObjectNode entry = values.addObject();
                    entry.put("key", kv.getKey());
                    entry.set("value", anyValueNode(kv.getValue()));
                }
            }
            case ARRAY_VALUE -> {
                ArrayNode values = node.putObject("arrayValue").putArray("values");
                for (AnyValue element : value.getArrayValue().getValuesList()) {
                    values.add(anyValueNode(element));
                }
            }
            default -> {
                // VALUE_NOT_SET：空对象
            }
        }
        return node;
    }

    private static String attributeValueAsString(AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INT_VALUE -> String.valueOf(value.getIntValue());
            case DOUBLE_VALUE -> String.valueOf(value.getDoubleValue());
            case BOOL_VALUE -> String.valueOf(value.getBoolValue());
            default -> "";
        };
    }

    /** OTLP/JSON 规范：trace_id/span_id 为小写十六进制（非 ProtoJSON base64）。 */
    private static String hex(com.google.protobuf.ByteString bytes) {
        char[] out = new char[bytes.size() * 2];
        for (int i = 0; i < bytes.size(); i++) {
            int b = bytes.byteAt(i) & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}

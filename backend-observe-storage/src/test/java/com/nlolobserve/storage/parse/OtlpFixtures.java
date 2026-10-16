package com.nlolobserve.storage.parse;

/**
 * T08 测试样例：与 T05 receiver OtlpJsonWriter 同口径的 OTLP/JSON 单记录信封
 * （camelCase 键、枚举数值、int64 字符串、traceId/spanId 为 SDK 原始 hex）。
 * 供 parser 单测、JDBC 仓库单测与 Testcontainers Databend 集成测试共用。
 */
public final class OtlpFixtures {

    /** 32 位 hex（OTLP/HTTP JSON 规范形态，T05 recoveredId 还原产物） */
    public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    /** 16 位 hex */
    public static final String SPAN_ID = "00f067aa0ba902b7";
    /** 2025-10-15T07:20:00Z（epoch 1760512800） */
    public static final long START_NANO = 1_760_512_800_000_000_000L;
    /** 2025-10-15T07:20:03Z */
    public static final long END_NANO = 1_760_512_803_000_000_000L;

    private OtlpFixtures() {
    }

    /** 单 span 信封（resource: nl.customer_id/service.name；scope: nl-observe；status code=1）。 */
    public static String spanEnvelope(String traceId, String spanId, String name, String spanAttributes) {
        return spanEnvelopeWithEvents(traceId, spanId, name, spanAttributes, "[]");
    }

    /** 单 span 信封（含 events，OTLP 接收端形态：timeUnixNano 字符串 + KeyValue attributes）。 */
    public static String spanEnvelopeWithEvents(String traceId, String spanId, String name,
                                                String spanAttributes, String events) {
        return "{\"resourceSpans\":[{\"resource\":{\"attributes\":["
                + "{\"key\":\"nl.customer_id\",\"value\":{\"stringValue\":\"c-1\"}},"
                + "{\"key\":\"service.name\",\"value\":{\"stringValue\":\"shop-web\"}}]},"
                + "\"scopeSpans\":[{\"scope\":{\"name\":\"nl-observe\"},\"spans\":[{"
                + "\"traceId\":\"" + traceId + "\",\"spanId\":\"" + spanId + "\","
                + "\"name\":\"" + name + "\",\"kind\":1,"
                + "\"startTimeUnixNano\":\"" + START_NANO + "\","
                + "\"endTimeUnixNano\":\"" + END_NANO + "\","
                + "\"attributes\":" + spanAttributes + ","
                + "\"events\":" + events + ","
                + "\"status\":{\"code\":1}}]}]}]}";
    }

    /**
     * 业务 span events 样例（T10/T13 口径：biz.suspend_start 带 biz.biz_id 属性、biz.resume 无属性）：
     * 2025-10-15T07:20:01Z / 07:20:02Z（START_NANO +1s/+2s）。
     */
    public static String bizEvents() {
        return "["
                + "{\"timeUnixNano\":\"" + (START_NANO + 1_000_000_000L) + "\","
                + "\"name\":\"biz.suspend_start\","
                + "\"attributes\":[{\"key\":\"biz.biz_id\",\"value\":{\"stringValue\":\"b-1\"}}]},"
                + "{\"timeUnixNano\":\"" + (START_NANO + 2_000_000_000L) + "\","
                + "\"name\":\"biz.resume\",\"attributes\":[]}"
                + "]";
    }

    /** 业务 root span 属性（T12 口径：biz.* 结束属性齐全）。 */
    public static String bizRootAttributes() {
        return "["
                + "{\"key\":\"nl.customer_id\",\"value\":{\"stringValue\":\"c-1\"}},"
                + "{\"key\":\"biz.code\",\"value\":{\"stringValue\":\"ORDER\"}},"
                + "{\"key\":\"biz.status\",\"value\":{\"stringValue\":\"success\"}},"
                + "{\"key\":\"biz.end_reason\",\"value\":{\"stringValue\":\"explicit\"}},"
                + "{\"key\":\"biz.total_suspended_ms\",\"value\":{\"intValue\":\"100\"}},"
                + "{\"key\":\"biz.effective_duration_ms\",\"value\":{\"intValue\":\"250\"}}"
                + "]";
    }

    /** 样例 span 信封：业务 root（ORDER，success，effective 250ms）。 */
    public static String sampleSpans() {
        return spanEnvelope(TRACE_ID, SPAN_ID, "biz.business.ORDER", bizRootAttributes());
    }

    /** 样例 metrics 信封：首屏 gauge（1000.0）+ 业务有效耗时 sum（asInt 250）。 */
    public static String sampleMetrics() {
        String gauge = "{\"name\":\"nl.first_screen_load\",\"unit\":\"ms\","
                + "\"gauge\":{\"dataPoints\":[{\"attributes\":"
                + "[{\"key\":\"page\",\"value\":{\"stringValue\":\"/checkout\"}}],"
                + "\"startTimeUnixNano\":\"1760512800000000000\","
                + "\"timeUnixNano\":\"1760512802000000000\",\"asDouble\":1000.0}]}}";
        String sum = "{\"name\":\"nl.business_duration\",\"unit\":\"ms\","
                + "\"sum\":{\"dataPoints\":[{\"attributes\":["
                + "{\"key\":\"biz.code\",\"value\":{\"stringValue\":\"ORDER\"}},"
                + "{\"key\":\"biz.status\",\"value\":{\"stringValue\":\"success\"}},"
                + "{\"key\":\"biz.trace_id\",\"value\":{\"stringValue\":\"" + TRACE_ID + "\"}}],"
                + "\"startTimeUnixNano\":\"1760512800000000000\","
                + "\"timeUnixNano\":\"1760512803000000000\",\"asInt\":\"250\"}],"
                + "\"aggregationTemporality\":2,\"isMonotonic\":false}}";
        return "{\"resourceMetrics\":[{\"resource\":{\"attributes\":["
                + "{\"key\":\"nl.customer_id\",\"value\":{\"stringValue\":\"c-1\"}}]},"
                + "\"scopeMetrics\":[{\"scope\":{\"name\":\"nl-observe\"},\"metrics\":["
                + gauge + "," + sum + "]}]}]}]}";
    }

    /** 单 gauge data point 信封（dp 属性可变，供 dp_hash 区分性用例）。 */
    public static String gaugeMetricEnvelope(String metricName, String timeNano, double value, String dpAttributes) {
        return metricShell(metricName,
                "\"gauge\":{\"dataPoints\":[{\"attributes\":" + dpAttributes
                        + ",\"startTimeUnixNano\":\"" + timeNano + "\","
                        + "\"timeUnixNano\":\"" + timeNano + "\","
                        + "\"asDouble\":" + value + "}]}");
    }

    private static String metricShell(String metricName, String dataNode) {
        return "{\"resourceMetrics\":[{\"resource\":{\"attributes\":["
                + "{\"key\":\"nl.customer_id\",\"value\":{\"stringValue\":\"c-1\"}}]},"
                + "\"scopeMetrics\":[{\"scope\":{\"name\":\"nl-observe\"},\"metrics\":["
                + "{\"name\":\"" + metricName + "\"," + dataNode + "}]}]}]}]}";
    }

    /** 单 log record 信封（severity INFO；traceId/spanId 可为 null 省略）。 */
    public static String logEnvelope(String body, String traceId, String spanId, String logAttributes) {
        return "{\"resourceLogs\":[{\"resource\":{\"attributes\":["
                + "{\"key\":\"nl.customer_id\",\"value\":{\"stringValue\":\"c-1\"}}]},"
                + "\"scopeLogs\":[{\"scope\":{\"name\":\"app\"},\"logRecords\":[{"
                + "\"timeUnixNano\":\"1760512805000000000\","
                + "\"severityNumber\":9,\"severityText\":\"INFO\","
                + "\"body\":{\"stringValue\":\"" + body + "\"},"
                + "\"attributes\":" + logAttributes
                + (traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"")
                + (spanId == null ? "" : ",\"spanId\":\"" + spanId + "\"")
                + "}]}]}]}";
    }

    /** 样例 log 信封（关联 trace/span，body 为字符串）。 */
    public static String sampleLogs() {
        return logEnvelope("user logged in", TRACE_ID, SPAN_ID,
                "[{\"key\":\"biz.code\",\"value\":{\"stringValue\":\"ORDER\"}}]");
    }
}

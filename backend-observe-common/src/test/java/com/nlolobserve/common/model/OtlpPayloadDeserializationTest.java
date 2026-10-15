package com.nlolobserve.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * T02 测试用例：OtlpTracePayload 反序列化样例 JSON 字段正确（metrics/logs 载荷一并覆盖）。
 */
class OtlpPayloadDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String readResource(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, "样例资源缺失: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void tracePayloadDeserializesSampleJsonFields() throws Exception {
        OtlpTracePayload payload = mapper.readValue(readResource("/otlp/sample-traces.json"), OtlpTracePayload.class);

        assertEquals(1, payload.getResourceSpans().size());
        OtlpTracePayload.ResourceSpans rs = payload.getResourceSpans().get(0);
        assertEquals("nl-observe-web", stringAttr(rs.getResource().getAttributes(), "service.name"));
        assertEquals(1, rs.getScopeSpans().size());
        assertEquals("sdk-web", rs.getScopeSpans().get(0).getScope().getName());

        List<OtlpTracePayload.Span> spans = rs.getScopeSpans().get(0).getSpans();
        OtlpTracePayload.Span root = spans.get(0);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", root.getTraceId());
        assertEquals("00000000000000f1", root.getSpanId());
        assertNull(root.getParentSpanId());
        assertEquals("biz.business.ORDER", root.getName());
        assertEquals(1, root.getKind());
        assertEquals("1759020000000000000", root.getStartTimeUnixNano());
        assertEquals("1759020045210000000", root.getEndTimeUnixNano());
        assertEquals("success", stringAttr(root.getAttributes(), "biz.status"));
        assertEquals(2, root.getEvents().size());
        assertEquals("biz.suspend_start", root.getEvents().get(0).getName());
        assertEquals("biz.resume", root.getEvents().get(1).getName());
        assertEquals(1, root.getStatus().getCode());

        OtlpTracePayload.Span rest = spans.get(1);
        assertEquals("00000000000000f2", rest.getSpanId());
        assertEquals("00000000000000f1", rest.getParentSpanId());
        assertEquals("HTTP POST", rest.getName());
        assertEquals("200", stringAttr(rest.getAttributes(), "http.response.status_code"));
        assertEquals("POST", stringAttr(rest.getAttributes(), "http.request.method"));
        assertEquals("/api/order/submit", stringAttr(rest.getAttributes(), "http.route"));
        assertEquals("45", stringAttr(rest.getAttributes(), "nl.duration_ms"));
    }

    @Test
    void metricPayloadDeserializesSampleJsonFields() throws Exception {
        OtlpMetricPayload payload = mapper.readValue(readResource("/otlp/sample-metrics.json"), OtlpMetricPayload.class);

        assertEquals(1, payload.getResourceMetrics().size());
        List<OtlpMetricPayload.Metric> metrics =
                payload.getResourceMetrics().get(0).getScopeMetrics().get(0).getMetrics();

        OtlpMetricPayload.Metric first = metrics.get(0);
        assertEquals("nl.first_screen_load", first.getName());
        assertEquals("ms", first.getUnit());
        assertNotNull(first.getGauge());
        assertEquals(1, first.getGauge().getDataPoints().size());
        OtlpMetricPayload.DataPoint dp = first.getGauge().getDataPoints().get(0);
        assertEquals("1759020001234000000", dp.getTimeUnixNano());
        assertEquals(1830.5, dp.getAsDouble(), 1e-9);
        assertEquals("CUST-001", stringAttr(dp.getAttributes(), "nl.customer_id"));

        OtlpMetricPayload.Metric second = metrics.get(1);
        assertEquals("nl.business_duration", second.getName());
        assertNotNull(second.getSum());
        assertTrue(second.getSum().isMonotonic());
        assertEquals(45210.0, second.getSum().getDataPoints().get(0).getAsDouble(), 1e-9);
        assertEquals("ORDER", stringAttr(second.getSum().getDataPoints().get(0).getAttributes(), "biz.code"));
    }

    @Test
    void logPayloadDeserializesSampleJsonFields() throws Exception {
        OtlpLogPayload payload = mapper.readValue(readResource("/otlp/sample-logs.json"), OtlpLogPayload.class);

        assertEquals(1, payload.getResourceLogs().size());
        OtlpLogPayload.LogRecord record =
                payload.getResourceLogs().get(0).getScopeLogs().get(0).getLogRecords().get(0);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", record.getTraceId());
        assertEquals("00000000000000f1", record.getSpanId());
        assertEquals("1759020000500000000", record.getTimeUnixNano());
        assertEquals(9, record.getSeverityNumber());
        assertEquals("INFO", record.getSeverityText());
        assertEquals("order submitted", record.getBody().getStringValue());
        assertEquals("CUST-001", stringAttr(record.getAttributes(), "nl.customer_id"));
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() throws Exception {
        String json = "{\"resourceSpans\":[{\"resource\":{},\"scopeSpans\":[],\"futureField\":1}],\"futureTopLevel\":2}";
        OtlpTracePayload payload = mapper.readValue(json, OtlpTracePayload.class);
        assertEquals(1, payload.getResourceSpans().size());
    }

    private static String stringAttr(List<KeyValue> attributes, String key) {
        return attributes.stream()
                .filter(kv -> key.equals(kv.getKey()))
                .map(kv -> kv.getValue().getStringValue())
                .findFirst()
                .orElse(null);
    }
}

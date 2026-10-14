package com.nlolobserve.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.dto.SpanDetail;
import com.nlolobserve.common.dto.SpanNode;
import com.nlolobserve.common.web.ApiResult;
import com.nlolobserve.common.web.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * T02：模型 POJO 往返（构造→序列化→反序列化）与 DTO/ApiResult 剩余访问器，保证契约字段完整。
 */
class OtlpModelRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tracePayloadRoundTripCoversLinksAndAllAnyValueForms() throws Exception {
        OtlpTracePayload payload = new OtlpTracePayload();
        OtlpTracePayload.ResourceSpans rs = new OtlpTracePayload.ResourceSpans();
        Resource resource = new Resource();
        resource.setAttributes(List.of(kv("service.name", "web")));
        resource.setDroppedAttributesCount(1);
        rs.setResource(resource);
        OtlpTracePayload.ScopeSpans ss = new OtlpTracePayload.ScopeSpans();
        InstrumentationScope scope = new InstrumentationScope();
        scope.setName("sdk-web");
        scope.setVersion("0.1.0");
        ss.setScope(scope);

        OtlpTracePayload.Span span = new OtlpTracePayload.Span();
        span.setTraceId("t1");
        span.setSpanId("s1");
        span.setParentSpanId("p1");
        span.setTraceState("rojo=00f067aa9ba13f3f");
        span.setName("HTTP GET");
        span.setKind(2);
        span.setStartTimeUnixNano("1");
        span.setEndTimeUnixNano("2");
        span.setAttributes(List.of(kv("http.route", "/x")));
        OtlpTracePayload.SpanEvent event = new OtlpTracePayload.SpanEvent();
        event.setTimeUnixNano("1500");
        event.setName("biz.resume");
        event.setAttributes(List.of());
        event.setDroppedAttributesCount(0);
        span.setEvents(List.of(event));
        OtlpTracePayload.SpanLink link = new OtlpTracePayload.SpanLink();
        link.setTraceId("t0");
        link.setSpanId("s0");
        link.setTraceState("ts");
        link.setAttributes(List.of(kv("nl.customer_id", "C1")));
        link.setDroppedAttributesCount(2);
        span.setLinks(List.of(link));
        OtlpTracePayload.Status status = new OtlpTracePayload.Status();
        status.setCode(2);
        status.setMessage("error");
        span.setStatus(status);
        ss.setSpans(List.of(span));
        rs.setScopeSpans(List.of(ss));
        payload.setResourceSpans(List.of(rs));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(payload));
        assertEquals("t0", json.get("resourceSpans").get(0).get("scopeSpans").get(0)
                .get("spans").get(0).get("links").get(0).get("traceId").asText());

        OtlpTracePayload back = mapper.readValue(json.toString(), OtlpTracePayload.class);
        OtlpTracePayload.Span backSpan = back.getResourceSpans().get(0).getScopeSpans().get(0).getSpans().get(0);
        assertEquals("rojo=00f067aa9ba13f3f", backSpan.getTraceState());
        assertEquals(2, backSpan.getKind());
        assertEquals("error", backSpan.getStatus().getMessage());
        assertEquals(1, backSpan.getLinks().size());
        assertEquals(2, backSpan.getLinks().get(0).getDroppedAttributesCount());
        assertEquals("biz.resume", backSpan.getEvents().get(0).getName());
        assertEquals("1500", backSpan.getEvents().get(0).getTimeUnixNano());
        assertEquals(1, back.getResourceSpans().get(0).getResource().getDroppedAttributesCount());
        assertEquals("0.1.0", back.getResourceSpans().get(0).getScopeSpans().get(0).getScope().getVersion());
    }

    @Test
    void anyValueCarriesAllForms() throws Exception {
        AnyValue v = new AnyValue();
        v.setStringValue("s");
        v.setIntValue("42");
        v.setDoubleValue(1.5);
        v.setBoolValue(true);
        v.setBytesValue("YmFzZTY0");
        JsonNode node = mapper.readTree(mapper.writeValueAsString(v));
        assertEquals("s", node.get("stringValue").asText());
        assertEquals("42", node.get("intValue").asText());
        assertEquals(1.5, node.get("doubleValue").asDouble(), 1e-9);
        assertTrue(node.get("boolValue").asBoolean());
        assertEquals("YmFzZTY0", node.get("bytesValue").asText());
    }

    @Test
    void metricPayloadHistogramAndIntPointsRoundTrip() throws Exception {
        OtlpMetricPayload payload = new OtlpMetricPayload();
        OtlpMetricPayload.ResourceMetrics rm = new OtlpMetricPayload.ResourceMetrics();
        rm.setResource(new Resource());
        OtlpMetricPayload.ScopeMetrics sm = new OtlpMetricPayload.ScopeMetrics();
        sm.setScope(new InstrumentationScope());
        OtlpMetricPayload.Metric metric = new OtlpMetricPayload.Metric();
        metric.setName("nl.sdk.flush");
        metric.setDescription("flush result");
        metric.setUnit("1");
        OtlpMetricPayload.Histogram histogram = new OtlpMetricPayload.Histogram();
        OtlpMetricPayload.DataPoint dp = new OtlpMetricPayload.DataPoint();
        dp.setAttributes(List.of(kv("nl.customer_id", "C1")));
        dp.setStartTimeUnixNano("10");
        dp.setTimeUnixNano("20");
        dp.setAsInt("7");
        histogram.setDataPoints(List.of(dp));
        histogram.setAggregationTemporality(2);
        metric.setHistogram(histogram);
        metric.setGauge(new OtlpMetricPayload.Gauge());
        sm.setMetrics(List.of(metric));
        rm.setScopeMetrics(List.of(sm));
        payload.setResourceMetrics(List.of(rm));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(payload));
        assertEquals("flush result", json.get("resourceMetrics").get(0).get("scopeMetrics").get(0)
                .get("metrics").get(0).get("description").asText());
        OtlpMetricPayload back = mapper.readValue(json.toString(), OtlpMetricPayload.class);
        OtlpMetricPayload.DataPoint backDp = back.getResourceMetrics().get(0).getScopeMetrics().get(0)
                .getMetrics().get(0).getHistogram().getDataPoints().get(0);
        assertEquals("7", backDp.getAsInt());
        assertEquals("20", backDp.getTimeUnixNano());
        assertNotNull(back.getResourceMetrics().get(0).getScopeMetrics().get(0).getMetrics().get(0).getGauge());
    }

    @Test
    void logPayloadRoundTripCoversRemainingFields() throws Exception {
        OtlpLogPayload payload = new OtlpLogPayload();
        OtlpLogPayload.ResourceLogs rl = new OtlpLogPayload.ResourceLogs();
        rl.setResource(new Resource());
        OtlpLogPayload.ScopeLogs sl = new OtlpLogPayload.ScopeLogs();
        sl.setScope(new InstrumentationScope());
        OtlpLogPayload.LogRecord record = new OtlpLogPayload.LogRecord();
        record.setTimeUnixNano("1");
        record.setObservedTimeUnixNano("2");
        record.setSeverityNumber(17);
        record.setSeverityText("ERROR");
        AnyValue body = new AnyValue();
        body.setStringValue("boom");
        record.setBody(body);
        record.setAttributes(List.of(kv("nl.customer_id", "C1")));
        record.setTraceId("t1");
        record.setSpanId("s1");
        record.setTraceState("ts");
        record.setDroppedAttributesCount(3);
        sl.setLogRecords(List.of(record));
        rl.setScopeLogs(List.of(sl));
        payload.setResourceLogs(List.of(rl));

        OtlpLogPayload back = mapper.readValue(mapper.writeValueAsString(payload), OtlpLogPayload.class);
        OtlpLogPayload.LogRecord backRecord = back.getResourceLogs().get(0).getScopeLogs().get(0).getLogRecords().get(0);
        assertEquals(17, backRecord.getSeverityNumber());
        assertEquals("ERROR", backRecord.getSeverityText());
        assertEquals("boom", backRecord.getBody().getStringValue());
        assertEquals("ts", backRecord.getTraceState());
        assertEquals(3, backRecord.getDroppedAttributesCount());
        assertEquals("2", backRecord.getObservedTimeUnixNano());
    }

    @Test
    void spanDetailRemainingAccessors() {
        SpanDetail detail = new SpanDetail();
        detail.setParentSpanId("p1");
        detail.setTraceState("ts");
        detail.setKind(3);
        detail.setStartTime("2026-09-28T09:00:00.000Z");
        detail.setEndTime("2026-09-28T09:00:01.000Z");
        detail.setStatusCode(1);
        detail.setDurationMs(1000);
        detail.setEffectiveDurationMs(900);
        detail.setBizCode("ORDER");
        detail.setServiceName("nl-observe-web");
        SpanNode.EventNode event = new SpanNode.EventNode();
        event.setTime("2026-09-28T09:00:00.500Z");
        event.setAttributes(java.util.Map.of("k", "v"));
        detail.setEvents(List.of(event));

        assertEquals("p1", detail.getParentSpanId());
        assertEquals("ts", detail.getTraceState());
        assertEquals(3, detail.getKind());
        assertEquals("2026-09-28T09:00:01.000Z", detail.getEndTime());
        assertEquals(1, detail.getStatusCode());
        assertEquals(1000, detail.getDurationMs(), 1e-9);
        assertEquals(900, detail.getEffectiveDurationMs(), 1e-9);
        assertEquals("ORDER", detail.getBizCode());
        assertEquals("nl-observe-web", detail.getServiceName());
        assertEquals("2026-09-28T09:00:00.500Z", detail.getEvents().get(0).getTime());
        assertEquals("v", detail.getEvents().get(0).getAttributes().get("k"));
    }

    @Test
    void apiResultBeanAccessorsRoundTrip() throws Exception {
        ApiResult<String> result = new ApiResult<>();
        result.setCode(ErrorCode.SUCCESS.getCode());
        result.setMessage(ErrorCode.SUCCESS.getDefaultMessage());
        result.setData("payload");
        ApiResult<String> back = mapper.readValue(mapper.writeValueAsString(result),
                mapper.getTypeFactory().constructParametricType(ApiResult.class, String.class));
        assertEquals(0, back.getCode());
        assertEquals("ok", back.getMessage());
        assertEquals("payload", back.getData());
        assertTrue(back.isSuccess());
    }

    private static KeyValue kv(String key, String value) {
        KeyValue kv = new KeyValue();
        kv.setKey(key);
        AnyValue anyValue = new AnyValue();
        anyValue.setStringValue(value);
        kv.setValue(anyValue);
        return kv;
    }
}

package com.nlolobserve.common.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * T02：查询端 DTO 与详细设计 §2 响应 JSON 结构一致（camelCase 字段、可被 Jackson 序列化）。
 */
class DtoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void businessItemMatchesBusinessesApiResponse() throws Exception {
        BusinessItem item = new BusinessItem();
        item.setBizId("b-001");
        item.setBizCode("ORDER");
        item.setTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
        item.setStartTime("2026-09-28T09:00:00.000Z");
        item.setEffectiveDurationMs(45210);
        item.setStatus("success");
        item.setEndReason("explicit");

        JsonNode node = mapper.readTree(mapper.writeValueAsString(item));
        assertEquals("b-001", node.get("bizId").asText());
        assertEquals("ORDER", node.get("bizCode").asText());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", node.get("traceId").asText());
        assertEquals("2026-09-28T09:00:00.000Z", node.get("startTime").asText());
        assertEquals(45210, node.get("effectiveDurationMs").asDouble(), 1e-9);
        assertEquals("success", node.get("status").asText());
        assertEquals("explicit", node.get("endReason").asText());
    }

    @Test
    void spanTreeMatchesTraceApiResponse() throws Exception {
        SpanNode root = new SpanNode();
        root.setSpanId("s1");
        root.setParentSpanId("");
        root.setName("biz.business.ORDER");
        root.setKind(1);
        root.setStartTime("2026-09-28T09:00:00.000Z");
        root.setEndTime("2026-09-28T09:00:45.210Z");
        root.setStatusCode(0);
        root.setDurationMs(45210);
        root.setEffectiveDurationMs(45210);
        root.setAttributes(Map.of("biz.status", "success"));
        SpanNode.EventNode event = new SpanNode.EventNode();
        event.setName("biz.suspend_start");
        root.setEvents(List.of(event));

        SpanTree tree = new SpanTree();
        tree.setTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
        tree.setSpans(List.of(root));

        JsonNode node = mapper.readTree(mapper.writeValueAsString(tree));
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", node.get("traceId").asText());
        JsonNode span = node.get("spans").get(0);
        assertEquals("s1", span.get("spanId").asText());
        assertEquals("biz.business.ORDER", span.get("name").asText());
        assertEquals("success", span.get("attributes").get("biz.status").asText());
        assertEquals("biz.suspend_start", span.get("events").get(0).get("name").asText());
    }

    @Test
    void spanDetailCarriesParsedJsonFields() throws Exception {
        SpanDetail detail = new SpanDetail();
        detail.setTraceId("t1");
        detail.setSpanId("s1");
        detail.setName("biz.business.ORDER");
        detail.setStatusMessage("");
        detail.setResource(Map.of("service.name", "nl-observe-web"));
        detail.setScope(Map.of("name", "sdk-web"));
        detail.setAttributes(Map.of("biz.status", "success"));

        JsonNode node = mapper.readTree(mapper.writeValueAsString(detail));
        assertEquals("t1", node.get("traceId").asText());
        assertEquals("nl-observe-web", node.get("resource").get("service.name").asText());
        assertEquals("sdk-web", node.get("scope").get("name").asText());
        assertTrue(node.has("attributes"));
    }

    @Test
    void metricSummaryMatchesMetricsSummaryApiResponse() throws Exception {
        BizMetric biz = new BizMetric();
        biz.setBizCode("ORDER");
        biz.setEffectiveDurationP95(52100.0);
        biz.setSuccessRate(0.982);
        biz.setCount(552);

        MetricSummary summary = new MetricSummary();
        summary.setFirstScreenLoadP95(1830.5);
        summary.setPerBiz(List.of(biz));

        JsonNode node = mapper.readTree(mapper.writeValueAsString(summary));
        assertEquals(1830.5, node.get("firstScreenLoadP95").asDouble(), 1e-9);
        JsonNode perBiz = node.get("perBiz").get(0);
        assertEquals("ORDER", perBiz.get("bizCode").asText());
        assertEquals(52100.0, perBiz.get("effectiveDurationP95").asDouble(), 1e-9);
        assertEquals(0.982, perBiz.get("successRate").asDouble(), 1e-9);
        assertEquals(552, perBiz.get("count").asLong());
    }
}

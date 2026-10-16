package com.nlolobserve.storage.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * T08 验收 #1（单元测试）：OTLP/JSON → 行模型。
 * - spans：提取 customer_id/biz_code/effective_duration_ms，保留 resource/scope/attributes JSON；
 *   traceId/spanId 原样入库（T05 已还原 SDK 原始 hex，存储端不得再转码）
 * - metrics：展开 data point，dp_hash 稳定且区分不同数据点（去重身份=聚簇键）
 * - logs：row_hash 稳定且区分不同记录
 */
class OtlpJsonParserTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final OtlpJsonParser parser = new OtlpJsonParser(mapper);

    @Test
    void parseSpans_extractsIdentityColumnsAndKeepsIdsVerbatim() {
        List<SpanRow> rows = parser.parseSpans(OtlpFixtures.sampleSpans());
        assertThat(rows).hasSize(1);
        SpanRow row = rows.get(0);
        assertThat(row.customerId()).isEqualTo("c-1");
        assertThat(row.bizCode()).isEqualTo("ORDER");
        assertThat(row.effectiveDurationMs()).isEqualTo(250.0);
        assertThat(row.serviceName()).isEqualTo("shop-web");
        assertThat(row.name()).isEqualTo("biz.business.ORDER");
        assertThat(row.kind()).isEqualTo(1);
        assertThat(row.statusCode()).isEqualTo(1);
        assertThat(row.parentSpanId()).isEmpty();
        assertThat(row.startTime()).isEqualTo(Instant.ofEpochSecond(1_760_512_800));
        assertThat(row.endTime()).isEqualTo(Instant.ofEpochSecond(1_760_512_803));
        // id 保真：SDK 原始 hex 串按 common 模型（String）原样入库，无 hex/base64 转码
        assertThat(row.traceId()).isEqualTo(OtlpFixtures.TRACE_ID);
        assertThat(row.spanId()).isEqualTo(OtlpFixtures.SPAN_ID);
    }

    @Test
    void parseSpans_keepsResourceScopeAttributesAsFlatJsonObjects() throws Exception {
        SpanRow row = parser.parseSpans(OtlpFixtures.sampleSpans()).get(0);

        Map<String, Object> resource = mapper.readValue(row.resourceJson(), MAP_TYPE);
        assertThat(resource).containsEntry("nl.customer_id", "c-1").containsEntry("service.name", "shop-web");

        Map<String, Object> scope = mapper.readValue(row.scopeJson(), MAP_TYPE);
        assertThat(scope).containsEntry("name", "nl-observe");

        Map<String, Object> attrs = mapper.readValue(row.attributesJson(), MAP_TYPE);
        assertThat(attrs).containsEntry("biz.status", "success").containsEntry("biz.code", "ORDER");
        assertThat(((Number) attrs.get("biz.total_suspended_ms")).longValue()).isEqualTo(100L);
        assertThat(((Number) attrs.get("biz.effective_duration_ms")).longValue()).isEqualTo(250L);
    }

    @Test
    void parseSpans_missingCustomerId_yieldsEmptyString() {
        String json = "{\"resourceSpans\":[{\"resource\":{\"attributes\":[]},\"scopeSpans\":["
                + "{\"scope\":{\"name\":\"x\"},\"spans\":[{\"traceId\":\"" + OtlpFixtures.TRACE_ID + "\","
                + "\"spanId\":\"" + OtlpFixtures.SPAN_ID + "\",\"name\":\"n\",\"kind\":1,"
                + "\"startTimeUnixNano\":\"1760512800000000000\","
                + "\"endTimeUnixNano\":\"1760512803000000000\",\"attributes\":[]}]}]}]}";
        SpanRow row = parser.parseSpans(json).get(0);
        assertThat(row.customerId()).isEmpty();
        assertThat(row.bizCode()).isEmpty();
        assertThat(row.serviceName()).isEmpty();
        assertThat(row.effectiveDurationMs()).isEqualTo(0.0);
    }

    @Test
    void parseSpans_bizCodeFallsBackToNamePrefix() {
        String noBizCodeAttrs = "[{\"key\":\"nl.customer_id\",\"value\":{\"stringValue\":\"c-1\"}}]";
        SpanRow row = parser.parseSpans(OtlpFixtures.spanEnvelope(OtlpFixtures.TRACE_ID, OtlpFixtures.SPAN_ID,
                "biz.business.PAY", noBizCodeAttrs)).get(0);
        assertThat(row.bizCode()).isEqualTo("PAY");
    }

    @Test
    void parseMetrics_expandsDataPoints() {
        List<MetricRow> rows = parser.parseMetrics(OtlpFixtures.sampleMetrics());
        assertThat(rows).hasSize(2);

        MetricRow firstScreen = rows.get(0);
        assertThat(firstScreen.customerId()).isEqualTo("c-1");
        assertThat(firstScreen.metricName()).isEqualTo("nl.first_screen_load");
        assertThat(firstScreen.metricType()).isEqualTo(1);
        assertThat(firstScreen.value()).isEqualTo(1000.0);
        assertThat(firstScreen.time()).isEqualTo(Instant.ofEpochSecond(1_760_512_802));
        assertThat(firstScreen.bizCode()).isEmpty();

        MetricRow duration = rows.get(1);
        assertThat(duration.metricName()).isEqualTo("nl.business_duration");
        assertThat(duration.metricType()).isEqualTo(2);
        assertThat(duration.value()).isEqualTo(250.0);
        assertThat(duration.time()).isEqualTo(Instant.ofEpochSecond(1_760_512_803));
        assertThat(duration.bizCode()).isEqualTo("ORDER");
    }

    @Test
    void dpHash_stableAcrossParsings() {
        List<MetricRow> a = parser.parseMetrics(OtlpFixtures.sampleMetrics());
        List<MetricRow> b = parser.parseMetrics(OtlpFixtures.sampleMetrics());
        assertThat(a).allSatisfy(r -> assertThat(r.dpHash()).isNotBlank());
        assertThat(a.stream().map(MetricRow::dpHash).toList())
                .isEqualTo(b.stream().map(MetricRow::dpHash).toList());
    }

    @Test
    void dpHash_distinguishesDifferentDataPoints() {
        String time = "1760512802000000000";
        String attrA = "[{\"key\":\"page\",\"value\":{\"stringValue\":\"/a\"}}]";
        String attrB = "[{\"key\":\"page\",\"value\":{\"stringValue\":\"/b\"}}]";
        MetricRow base = parser.parseMetrics(gauge("nl.first_screen_load", time, 1000.0, attrA)).get(0);
        // 同 (metric,time,attrs) 不同 value → 不同数据点
        MetricRow otherValue = parser.parseMetrics(gauge("nl.first_screen_load", time, 1001.0, attrA)).get(0);
        // 同 (metric,time,value) 不同属性 → 不同数据点
        MetricRow otherAttrs = parser.parseMetrics(gauge("nl.first_screen_load", time, 1000.0, attrB)).get(0);
        // 不同 time → 不同数据点
        MetricRow otherTime = parser.parseMetrics(gauge("nl.first_screen_load",
                "1760512803000000000", 1000.0, attrA)).get(0);

        assertThat(base.dpHash())
                .isNotEqualTo(otherValue.dpHash())
                .isNotEqualTo(otherAttrs.dpHash())
                .isNotEqualTo(otherTime.dpHash());
    }

    @Test
    void parseLogs_rowHashStableAndDistinguishing() {
        List<LogRow> a = parser.parseLogs(OtlpFixtures.sampleLogs());
        List<LogRow> b = parser.parseLogs(OtlpFixtures.sampleLogs());
        assertThat(a).hasSize(1);
        LogRow row = a.get(0);
        assertThat(row.customerId()).isEqualTo("c-1");
        assertThat(row.traceId()).isEqualTo(OtlpFixtures.TRACE_ID);
        assertThat(row.spanId()).isEqualTo(OtlpFixtures.SPAN_ID);
        assertThat(row.severityNumber()).isEqualTo(9);
        assertThat(row.severityText()).isEqualTo("INFO");
        assertThat(row.body()).isEqualTo("user logged in");
        assertThat(row.time()).isEqualTo(Instant.ofEpochSecond(1_760_512_805));
        assertThat(row.rowHash()).isNotBlank();

        assertThat(parser.parseLogs(OtlpFixtures.sampleLogs()).get(0).rowHash()).isEqualTo(row.rowHash());
        // 不同 body → 不同 row_hash
        LogRow other = parser.parseLogs(
                OtlpFixtures.logEnvelope("logout", OtlpFixtures.TRACE_ID, OtlpFixtures.SPAN_ID, "[]")).get(0);
        assertThat(other.rowHash()).isNotEqualTo(row.rowHash());
    }

    // ---- T10 契约：events_json 写路径（T08 评审 Major：events 不得解析后丢弃） ----

    @Test
    void parseSpans_eventsSerializedToQueryNativeFormat() throws Exception {
        String json = OtlpFixtures.spanEnvelopeWithEvents(OtlpFixtures.TRACE_ID, OtlpFixtures.SPAN_ID,
                "biz.business.ORDER", OtlpFixtures.bizRootAttributes(), OtlpFixtures.bizEvents());
        SpanRow row = parser.parseSpans(json).get(0);

        List<Map<String, Object>> events = mapper.readValue(row.eventsJson(), LIST_TYPE);
        assertThat(events).hasSize(2);
        // 契约形态：{"name":"...","time":"ISO-8601 UTC","attributes":{扁平键值}}
        assertThat(events.get(0)).containsEntry("name", "biz.suspend_start")
                .containsEntry("time", "2025-10-15T07:20:01Z");
        assertThat(asMap(events.get(0).get("attributes"))).containsEntry("biz.biz_id", "b-1");
        assertThat(events.get(1)).containsEntry("name", "biz.resume")
                .containsEntry("time", "2025-10-15T07:20:02Z");
        assertThat(asMap(events.get(1).get("attributes"))).isEmpty();
    }

    @Test
    void parseSpans_noEvents_yieldsEmptyArray() {
        SpanRow row = parser.parseSpans(OtlpFixtures.sampleSpans()).get(0);
        assertThat(row.eventsJson()).isEqualTo("[]");
    }

    // ---- T08 评审 F-2：显式 JSON null 字段归一为空，不得 NPE ----

    @Test
    void parseSpans_explicitNullFields_normalizedToEmpty() {
        String json = "{\"resourceSpans\":[{\"resource\":null,\"scopeSpans\":[{\"scope\":null,"
                + "\"spans\":[{\"traceId\":\"" + OtlpFixtures.TRACE_ID + "\","
                + "\"spanId\":\"" + OtlpFixtures.SPAN_ID + "\",\"name\":\"n\",\"kind\":1,"
                + "\"startTimeUnixNano\":\"1760512800000000000\","
                + "\"endTimeUnixNano\":\"1760512803000000000\","
                + "\"attributes\":null,\"events\":null,\"status\":null}]}]}]}";
        SpanRow row = parser.parseSpans(json).get(0);
        assertThat(row.customerId()).isEmpty();
        assertThat(row.attributesJson()).isEqualTo("{}");
        assertThat(row.scopeJson()).isEqualTo("{}");
        assertThat(row.eventsJson()).isEqualTo("[]");
        assertThat(row.statusCode()).isZero();
    }

    @Test
    void parseSpans_nullPayloadAndNullLists_noThrow() {
        assertThat(parser.parseSpans("null")).isEmpty();
        assertThat(parser.parseSpans("{\"resourceSpans\":[{\"resource\":null,\"scopeSpans\":null}]}")).isEmpty();
    }

    @Test
    void parseMetrics_explicitNulls_noThrow() {
        String json = "{\"resourceMetrics\":[{\"resource\":null,\"scopeMetrics\":[{\"metrics\":["
                + "{\"name\":\"m\",\"gauge\":{\"dataPoints\":null}}]}]}]}";
        assertThat(parser.parseMetrics(json)).isEmpty();
        assertThat(parser.parseMetrics("null")).isEmpty();
    }

    @Test
    void parseLogs_explicitNulls_noThrow() {
        String json = "{\"resourceLogs\":[{\"resource\":null,\"scopeLogs\":[{\"logRecords\":[{"
                + "\"timeUnixNano\":\"1760512805000000000\",\"body\":null,\"attributes\":null,"
                + "\"severityText\":null}]}]}]}";
        LogRow row = parser.parseLogs(json).get(0);
        assertThat(row.body()).isEmpty();
        assertThat(row.attributesJson()).isEqualTo("{}");
    }

    // ---- T08 评审 F-4：哈希字段边界不可被内容伪造（长度前缀编码） ----

    @Test
    void dpHash_delimiterAmbiguity_notCollided() {
        // 旧规范化口径下两串相同（"a=b\u001fc=d"）→ 可构造碰撞劫持 REPLACE INTO 他行（F-4 反例）
        String attrAmbiguous = "[{\"key\":\"a\",\"value\":{\"stringValue\":\"b\\u001fc=d\"}}]";
        String attrTwoEntries = "[{\"key\":\"a\",\"value\":{\"stringValue\":\"b\"}},"
                + "{\"key\":\"c\",\"value\":{\"stringValue\":\"d\"}}]";
        MetricRow x = parser.parseMetrics(gauge("m", "1760512802000000000", 1.0, attrAmbiguous)).get(0);
        MetricRow y = parser.parseMetrics(gauge("m", "1760512802000000000", 1.0, attrTwoEntries)).get(0);
        assertThat(x.dpHash()).isNotEqualTo(y.dpHash());
    }

    @Test
    void dpHash_includesServiceName_sameDataPointDifferentServiceNotFolded() {
        // 评审 Minor：dp_hash 不含 resource 身份 → 同客户多 service 相同 data point 静默折叠
        MetricRow a = parser.parseMetrics(gaugeWithService("svc-a")).get(0);
        MetricRow b = parser.parseMetrics(gaugeWithService("svc-b")).get(0);
        assertThat(a.dpHash()).isNotEqualTo(b.dpHash());
    }

    @Test
    void parseSpans_invalidJson_throwsIllegalArgument() {
        assertThatThrownBy(() -> parser.parseSpans("{not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseMetrics_invalidJson_throwsIllegalArgument() {
        assertThatThrownBy(() -> parser.parseMetrics("[broken"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String gauge(String metricName, String timeNano, double value, String dpAttributes) {
        return OtlpFixtures.gaugeMetricEnvelope(metricName, timeNano, value, dpAttributes);
    }

    /** 单 gauge data point 信封（resource 带 service.name，供 dp_hash service 身份用例）。 */
    private static String gaugeWithService(String serviceName) {
        return "{\"resourceMetrics\":[{\"resource\":{\"attributes\":["
                + "{\"key\":\"nl.customer_id\",\"value\":{\"stringValue\":\"c-1\"}},"
                + "{\"key\":\"service.name\",\"value\":{\"stringValue\":\"" + serviceName + "\"}}]},"
                + "\"scopeMetrics\":[{\"scope\":{\"name\":\"nl-observe\"},\"metrics\":[{"
                + "\"name\":\"m\",\"gauge\":{\"dataPoints\":[{"
                + "\"attributes\":[],\"timeUnixNano\":\"1760512802000000000\",\"asDouble\":1.0}]}}]}]}]}]}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}

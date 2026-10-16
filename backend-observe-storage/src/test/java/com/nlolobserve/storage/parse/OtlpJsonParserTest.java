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
}

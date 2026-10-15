package com.nlolobserve.receiver.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import com.nlolobserve.common.model.OtlpLogPayload;
import com.nlolobserve.common.model.OtlpMetricPayload;
import com.nlolobserve.common.model.OtlpTracePayload;
import io.mishmash.opentelemetry.server.collector.Batch;
import io.mishmash.opentelemetry.server.collector.Log;
import io.mishmash.opentelemetry.server.collector.MetricDataPoint;
import io.mishmash.opentelemetry.server.collector.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import org.junit.jupiter.api.Test;

/**
 * T05（单元）：OtlpJsonWriter 输出为规范 OTLP/JSON（详设 §1.2 载荷格式）——
 * camelCase 键、枚举数值、int64/uint64 字符串、trace/span id 十六进制；
 * 输出可被 common 侧 OTLP 模型解析（与 T08 存储端解析口径一致），key 提取 nl.customer_id。
 */
class OtlpJsonWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tracesEnvelope_otelJsonShape_parsableByCommonModel_andKeyExtracted() throws Exception {
        ExportTraceServiceRequest request = parse(
                "otlp/sample-traces.json", ExportTraceServiceRequest.getDefaultInstance());

        Span wrapper = new Span(new Batch<>(Context.root()), Context.root(), null);
        wrapper.setFrom(1L, "batch-1", 0,
                request.getResourceSpans(0), request.getResourceSpans(0).getScopeSpans(0),
                request.getResourceSpans(0).getScopeSpans(0).getSpans(0));

        String json = OtlpJsonWriter.tracesEnvelope(wrapper);

        // 形状断言：camelCase + 枚举数值 + uint64 字符串
        assertThat(json).contains("\"resourceSpans\"").contains("\"scopeSpans\"")
                .contains("\"nl.customer_id\"").contains("\"biz.business.ORDER\"")
                .contains("\"kind\":1").contains("\"startTimeUnixNano\":\"");

        // 可被 common 模型解析且字段保真
        OtlpTracePayload payload = mapper.readValue(json, OtlpTracePayload.class);
        List<OtlpTracePayload.ResourceSpans> resourceSpans = payload.getResourceSpans();
        assertThat(resourceSpans).hasSize(1);
        String customer = resourceSpans.get(0).getResource().getAttributes().stream()
                .filter(kv -> "nl.customer_id".equals(kv.getKey()))
                .findFirst().orElseThrow().getValue().getStringValue();
        assertThat(customer).isEqualTo("CUST-001");

        OtlpTracePayload.Span span = resourceSpans.get(0).getScopeSpans().get(0).getSpans().get(0);
        assertThat(span.getName()).isEqualTo("biz.business.ORDER");
        assertThat(span.getKind()).isEqualTo(1);

        // key = nl.customer_id
        assertThat(OtlpJsonWriter.customerId(wrapper.getResourceAttributes())).isEqualTo("CUST-001");
    }

    @Test
    void metricsEnvelope_otelJsonShape_parsableByCommonModel() throws Exception {
        ExportMetricsServiceRequest request = parse(
                "otlp/sample-metrics.json", ExportMetricsServiceRequest.getDefaultInstance());

        MetricDataPoint wrapper = new MetricDataPoint(new Batch<>(Context.root()), Context.root(), null);
        var resourceMetrics = request.getResourceMetrics(0);
        var scopeMetrics = resourceMetrics.getScopeMetrics(0);
        var metric = scopeMetrics.getMetrics(0);
        switch (metric.getDataCase()) {
            case GAUGE -> wrapper.setFrom(1L, "batch-1", 0, resourceMetrics, scopeMetrics,
                    metric, metric.getGauge(), 0, metric.getGauge().getDataPoints(0));
            case SUM -> wrapper.setFrom(1L, "batch-1", 0, resourceMetrics, scopeMetrics,
                    metric, metric.getSum(), 0, metric.getSum().getDataPoints(0));
            case HISTOGRAM -> wrapper.setFrom(1L, "batch-1", 0, resourceMetrics, scopeMetrics,
                    metric, metric.getHistogram(), 0, metric.getHistogram().getDataPoints(0));
            case EXPONENTIAL_HISTOGRAM -> wrapper.setFrom(1L, "batch-1", 0, resourceMetrics, scopeMetrics,
                    metric, metric.getExponentialHistogram(), 0, metric.getExponentialHistogram().getDataPoints(0));
            case SUMMARY -> wrapper.setFrom(1L, "batch-1", 0, resourceMetrics, scopeMetrics,
                    metric, metric.getSummary(), 0, metric.getSummary().getDataPoints(0));
            default -> throw new IllegalStateException("unsupported metric type: " + metric.getDataCase());
        }

        String json = OtlpJsonWriter.metricsEnvelope(wrapper);

        assertThat(json).contains("\"resourceMetrics\"").contains("\"scopeMetrics\"")
                .contains("\"nl.first_screen_load\"").contains("\"asDouble\":1830.5");

        OtlpMetricPayload payload = mapper.readValue(json, OtlpMetricPayload.class);
        assertThat(payload.getResourceMetrics()).hasSize(1);
        OtlpMetricPayload.Metric metricOut =
                payload.getResourceMetrics().get(0).getScopeMetrics().get(0).getMetrics().get(0);
        assertThat(metricOut.getName()).isEqualTo("nl.first_screen_load");
        assertThat(metricOut.getGauge().getDataPoints().get(0).getAsDouble()).isEqualTo(1830.5);
    }

    @Test
    void sumDataPoint_monotonicBoolAndTemporalityNumber() throws Exception {
        // business_duration 为 sum 型（asDouble），验证 isMonotonic 布尔 + aggregationTemporality 数值
        ExportMetricsServiceRequest request = parse(
                "otlp/sample-metrics.json", ExportMetricsServiceRequest.getDefaultInstance());
        var resourceMetrics = request.getResourceMetrics(0);
        var scopeMetrics = resourceMetrics.getScopeMetrics(0);
        var sumMetric = scopeMetrics.getMetrics(1);

        MetricDataPoint wrapper = new MetricDataPoint(new Batch<>(Context.root()), Context.root(), null);
        wrapper.setFrom(1L, "batch-1", 1, resourceMetrics, scopeMetrics,
                sumMetric, sumMetric.getSum(), 0, sumMetric.getSum().getDataPoints(0));

        String json = OtlpJsonWriter.metricsEnvelope(wrapper);
        assertThat(json).contains("\"nl.business_duration\"").contains("\"sum\":{")
                .contains("\"isMonotonic\":true").contains("\"aggregationTemporality\":2");

        OtlpMetricPayload payload = mapper.readValue(json, OtlpMetricPayload.class);
        OtlpMetricPayload.Metric out =
                payload.getResourceMetrics().get(0).getScopeMetrics().get(0).getMetrics().get(0);
        assertThat(out.getName()).isEqualTo("nl.business_duration");
        assertThat(out.getSum().isMonotonic()).isTrue();
        assertThat(out.getSum().getAggregationTemporality()).isEqualTo(2);
        assertThat(out.getSum().getDataPoints().get(0).getAsDouble()).isEqualTo(45210.0);
    }

    @Test
    void gaugeDataPoint_asInt64RenderedAsString() throws Exception {
        // 合成 asInt gauge 数据点：int64 在 OTLP/JSON 中必须为字符串（common 模型 asInt 为 String）
        var dp = io.opentelemetry.proto.metrics.v1.NumberDataPoint.newBuilder()
                .setStartTimeUnixNano(1759020000000000000L)
                .setTimeUnixNano(1759020001000000000L)
                .setAsInt(42L)
                .build();
        var metric = io.opentelemetry.proto.metrics.v1.Metric.newBuilder()
                .setName("nl.test_int").setUnit("1")
                .setGauge(io.opentelemetry.proto.metrics.v1.Gauge.newBuilder().addDataPoints(dp))
                .build();
        var resource = io.opentelemetry.proto.resource.v1.Resource.newBuilder()
                .addAttributes(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
                        .setKey("nl.customer_id")
                        .setValue(io.opentelemetry.proto.common.v1.AnyValue.newBuilder()
                                .setStringValue("C-INT").build()))
                .build();
        var resourceMetrics = io.opentelemetry.proto.metrics.v1.ResourceMetrics.newBuilder()
                .setResource(resource)
                .addScopeMetrics(io.opentelemetry.proto.metrics.v1.ScopeMetrics.newBuilder().addMetrics(metric))
                .build();
        var scopeMetrics = resourceMetrics.getScopeMetrics(0);
        var metricOut = scopeMetrics.getMetrics(0);

        MetricDataPoint wrapper = new MetricDataPoint(new Batch<>(Context.root()), Context.root(), null);
        wrapper.setFrom(1L, "batch-1", 0, resourceMetrics, scopeMetrics,
                metricOut, metricOut.getGauge(), 0, metricOut.getGauge().getDataPoints(0));

        String json = OtlpJsonWriter.metricsEnvelope(wrapper);
        assertThat(json).contains("\"asInt\":\"42\"");

        OtlpMetricPayload payload = mapper.readValue(json, OtlpMetricPayload.class);
        OtlpMetricPayload.Metric out =
                payload.getResourceMetrics().get(0).getScopeMetrics().get(0).getMetrics().get(0);
        assertThat(out.getGauge().getDataPoints().get(0).getAsInt()).isEqualTo("42");
    }

    @Test
    void logsEnvelope_otelJsonShape_parsableByCommonModel() throws Exception {
        ExportLogsServiceRequest request = parse(
                "otlp/sample-logs.json", ExportLogsServiceRequest.getDefaultInstance());

        Log wrapper = new Log(new Batch<>(Context.root()), Context.root(), null);
        var resourceLogs = request.getResourceLogs(0);
        var scopeLogs = resourceLogs.getScopeLogs(0);
        wrapper.setFrom(1L, "batch-1", 0, resourceLogs, scopeLogs, scopeLogs.getLogRecords(0));

        String json = OtlpJsonWriter.logsEnvelope(wrapper);

        assertThat(json).contains("\"resourceLogs\"").contains("\"scopeLogs\"")
                .contains("\"order submitted\"").contains("\"severityNumber\":9");

        OtlpLogPayload payload = mapper.readValue(json, OtlpLogPayload.class);
        assertThat(payload.getResourceLogs()).hasSize(1);
        OtlpLogPayload.LogRecord record =
                payload.getResourceLogs().get(0).getScopeLogs().get(0).getLogRecords().get(0);
        assertThat(record.getSeverityNumber()).isEqualTo(9);
        assertThat(record.getBody().getStringValue()).isEqualTo("order submitted");
    }

    @Test
    void customerId_missingAttribute_fallsBackToEmpty() {
        assertThat(OtlpJsonWriter.customerId(List.of())).isEmpty();
    }

    /** protobuf 4.x：Parser 不再暴露 newBuilderForType，改以 prototype 取 builder。 */
    private static <T extends com.google.protobuf.Message> T parse(String resource, T prototype) {
        try (InputStream in = OtlpJsonWriterTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("测试资源 %s 必须存在", resource).isNotNull();
            var builder = prototype.toBuilder();
            try {
                JsonFormat.parser().merge(new StringReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
                        builder);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            @SuppressWarnings("unchecked")
            T message = (T) builder.build();
            return message;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

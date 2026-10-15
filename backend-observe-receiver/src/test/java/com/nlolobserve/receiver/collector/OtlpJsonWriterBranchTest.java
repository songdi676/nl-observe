package com.nlolobserve.receiver.collector;

import java.util.List;

import com.google.protobuf.ByteString;
import io.mishmash.opentelemetry.server.collector.Batch;
import io.mishmash.opentelemetry.server.collector.Log;
import io.mishmash.opentelemetry.server.collector.MetricDataPoint;
import io.mishmash.opentelemetry.server.collector.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogram;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Summary;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T05（单元）：OtlpJsonWriter 合成分支覆盖——links/events/traceState/dropped 计数、
 * AnyValue 全类型（bool/double/bytes/kvlist/array）、histogram/exponentialHistogram/summary、
 * VALUE_NOT_SET 数值点、空 scope/name 省略分支、customerId 非字符串回退。
 * id 字段断言口径（T05 修复轮）：writer 对 id 输出 base64(字节串)——对经 OTLP/HTTP JSON 入口
 * 的真实链路（collector-embedded 把 hex id 按 base64 解码成字节），该编码精确还原 SDK 原始 hex 串
 * （见 OtlpJsonWriterTest/KafkaThreeTopicsRoutingTest 的保真断言）；本类合成的是任意原始字节串，
 * 断言其 base64 输出值。
 */
class OtlpJsonWriterBranchTest {

    private static final ByteString TRACE_ID = ByteString.copyFromUtf8("0123456789abcdef");
    private static final ByteString SPAN_ID = ByteString.copyFromUtf8("01234567");

    @Test
    void tracesEnvelope_coversLinksEventsTraceStateDroppedAndAnyValueTypes() {
        Resource resource = Resource.newBuilder()
                .addAttributes(kv("nl.customer_id", AnyValue.newBuilder().setBoolValue(true).build()))
                .addAttributes(kv("d", AnyValue.newBuilder().setDoubleValue(1.5).build()))
                .addAttributes(kv("b", AnyValue.newBuilder().setBytesValue(ByteString.copyFromUtf8("x")).build()))
                .addAttributes(kv("kv", AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
                        .addValues(kv("inner", AnyValue.newBuilder().setIntValue(7).build()))).build()))
                .addAttributes(kv("arr", AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
                        .addValues(AnyValue.newBuilder().setStringValue("s").build())).build()))
                .setDroppedAttributesCount(2)
                .build();
        io.opentelemetry.proto.trace.v1.Span span = io.opentelemetry.proto.trace.v1.Span.newBuilder()
                .setTraceId(TRACE_ID).setSpanId(SPAN_ID)
                .setParentSpanId(ByteString.copyFromUtf8("76543210"))
                .setTraceState("k=v")
                .setName("n").setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_INTERNAL)
                .setStartTimeUnixNano(1L).setEndTimeUnixNano(2L)
                .addEvents(io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                        .setName("ev").setTimeUnixNano(3L)
                        .addAttributes(kv("a", str("b"))).setDroppedAttributesCount(1).build())
                .addLinks(io.opentelemetry.proto.trace.v1.Span.Link.newBuilder()
                        .setTraceId(TRACE_ID).setSpanId(SPAN_ID).setTraceState("t=1")
                        .addAttributes(kv("l", str("m"))).setDroppedAttributesCount(1).build())
                .setDroppedAttributesCount(3)
                .setStatus(Status.newBuilder()
                        .setCode(Status.StatusCode.STATUS_CODE_ERROR).setMessage("boom").build())
                .build();
        var resourceSpans = io.opentelemetry.proto.trace.v1.ResourceSpans.newBuilder()
                .setResource(resource)
                .addScopeSpans(io.opentelemetry.proto.trace.v1.ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder().setName("s").setVersion("v")
                                .addAttributes(kv("sk", str("sv"))).setDroppedAttributesCount(1).build())
                        .addSpans(span).build())
                .build();

        Span wrapper = new Span(new Batch<>(Context.root()), Context.root(), null);
        wrapper.setFrom(1L, "b", 0, resourceSpans, resourceSpans.getScopeSpans(0), span);

        String json = OtlpJsonWriter.tracesEnvelope(wrapper);
        assertThat(json)
                .contains("\"traceState\":\"k=v\"").contains("\"parentSpanId\":\"NzY1NDMyMTA=\"")
                .contains("\"boolValue\":true").contains("\"doubleValue\":1.5")
                .contains("\"bytesValue\":\"eA==")
                .contains("\"kvlistValue\"").contains("\"intValue\":\"7\"")
                .contains("\"arrayValue\"").contains("\"stringValue\":\"s\"")
                .contains("\"droppedAttributesCount\":2").contains("\"droppedAttributesCount\":3")
                .contains("\"events\":[{\"timeUnixNano\":\"3\",\"name\":\"ev\"")
                .contains("\"links\":[{\"traceId\":\"MDEyMzQ1Njc4OWFiY2RlZg==\"")
                .contains("\"status\":{\"code\":2,\"message\":\"boom\"}");

        // customerId 非字符串属性：bool → "true"
        assertThat(OtlpJsonWriter.customerId(wrapper.getResourceAttributes())).isEqualTo("true");
    }

    @Test
    void customerId_bytesAttribute_fallsBackToEmpty() {
        KeyValue kv = kv("nl.customer_id",
                AnyValue.newBuilder().setBytesValue(ByteString.copyFromUtf8("x")).build());
        assertThat(OtlpJsonWriter.customerId(List.of(kv))).isEmpty();
    }

    @Test
    void metricsEnvelope_coversHistogramExpHistogramSummaryAndValueNotSet() {
        Metric histogramMetric = Metric.newBuilder().setName("h")
                .setHistogram(Histogram.newBuilder()
                        .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA)
                        .addDataPoints(HistogramDataPoint.newBuilder()
                                .setStartTimeUnixNano(1L).setTimeUnixNano(2L).setCount(3L)
                                .setSum(9.0).addBucketCounts(1L).addBucketCounts(2L)
                                .addExplicitBounds(0.5).setMin(0.1).setMax(0.9).build())
                        .build())
                .build();
        Metric exponentialMetric = Metric.newBuilder().setName("e")
                .setExponentialHistogram(ExponentialHistogram.newBuilder()
                        .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                        .addDataPoints(ExponentialHistogramDataPoint.newBuilder()
                                .setStartTimeUnixNano(1L).setTimeUnixNano(2L).setCount(4L).setSum(10.0)
                                .setScale(2).setZeroCount(1L)
                                .setPositive(ExponentialHistogramDataPoint.Buckets.newBuilder()
                                        .setOffset(1).addBucketCounts(2L).build())
                                .setNegative(ExponentialHistogramDataPoint.Buckets.newBuilder()
                                        .setOffset(-1).addBucketCounts(1L).build())
                                .setMin(0.2).setMax(1.3).build())
                        .build())
                .build();
        Metric summaryMetric = Metric.newBuilder().setName("s").setDescription("desc").setUnit("ms")
                .setSummary(Summary.newBuilder()
                        .addDataPoints(SummaryDataPoint.newBuilder()
                                .setStartTimeUnixNano(1L).setTimeUnixNano(2L).setCount(5L).setSum(20.0)
                                .addQuantileValues(SummaryDataPoint.ValueAtQuantile.newBuilder()
                                        .setQuantile(0.5).setValue(2.0).build())
                                .build())
                        .build())
                .build();
        Metric emptyValueMetric = Metric.newBuilder().setName("g")
                .setGauge(Gauge.newBuilder()
                        .addDataPoints(NumberDataPoint.newBuilder()
                                .setStartTimeUnixNano(1L).setTimeUnixNano(2L).build())
                        .build())
                .build();

        // 空 scope（无 name/version/attributes）：省略分支
        ResourceMetrics resourceMetrics = ResourceMetrics.newBuilder()
                .setResource(Resource.newBuilder()
                        .addAttributes(kv("nl.customer_id", str("C-1"))).build())
                .addScopeMetrics(ScopeMetrics.newBuilder()
                        .addMetrics(histogramMetric).addMetrics(exponentialMetric)
                        .addMetrics(summaryMetric).addMetrics(emptyValueMetric).build())
                .build();
        ScopeMetrics scopeMetrics = resourceMetrics.getScopeMetrics(0);

        String histogramJson = envelopeFor(resourceMetrics, scopeMetrics, 0, histogramMetric);
        assertThat(histogramJson).contains("\"histogram\":{")
                .contains("\"bucketCounts\":[\"1\",\"2\"]").contains("\"explicitBounds\":[0.5]")
                .contains("\"aggregationTemporality\":1")
                .contains("\"min\":0.1").contains("\"max\":0.9");

        String exponentialJson = envelopeFor(resourceMetrics, scopeMetrics, 1, exponentialMetric);
        assertThat(exponentialJson).contains("\"exponentialHistogram\":{")
                .contains("\"scale\":2").contains("\"zeroCount\":\"1\"")
                .contains("\"positive\":{\"offset\":1,\"bucketCounts\":[\"2\"]}")
                .contains("\"negative\":{\"offset\":-1,\"bucketCounts\":[\"1\"]}")
                .contains("\"aggregationTemporality\":2");

        String summaryJson = envelopeFor(resourceMetrics, scopeMetrics, 2, summaryMetric);
        assertThat(summaryJson).contains("\"summary\":{").contains("\"quantileValues\":[")
                .contains("\"description\":\"desc\"").contains("\"unit\":\"ms\"");

        String emptyValueJson = envelopeFor(resourceMetrics, scopeMetrics, 3, emptyValueMetric);
        assertThat(emptyValueJson).contains("\"gauge\":{\"dataPoints\":[{\"attributes\":[]")
                .doesNotContain("asDouble").doesNotContain("asInt");
    }

    @Test
    void logsEnvelope_coversFlagsIdsDroppedAndNoBody() {
        ResourceLogs resourceLogs = ResourceLogs.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(kv("nl.customer_id", str("C-2"))).build())
                .addScopeLogs(ScopeLogs.newBuilder()
                        .addLogRecords(io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
                                .setTimeUnixNano(1L).setObservedTimeUnixNano(2L)
                                .setSeverityNumber(io.opentelemetry.proto.logs.v1.SeverityNumber.SEVERITY_NUMBER_WARN)
                                .setSeverityText("WARN")
                                .addAttributes(kv("a", str("b"))).setDroppedAttributesCount(1)
                                .setFlags(0x01000000).setTraceId(TRACE_ID).setSpanId(SPAN_ID)
                                .build())
                        .build())
                .build();
        var scopeLogs = resourceLogs.getScopeLogs(0);
        Log wrapper = new Log(new Batch<>(Context.root()), Context.root(), null);
        wrapper.setFrom(1L, "b", 0, resourceLogs, scopeLogs, scopeLogs.getLogRecords(0));

        String json = OtlpJsonWriter.logsEnvelope(wrapper);
        assertThat(json).contains("\"severityNumber\":13").contains("\"severityText\":\"WARN\"")
                .contains("\"flags\":16777216")
                .contains("\"traceId\":\"MDEyMzQ1Njc4OWFiY2RlZg==\"")
                .contains("\"spanId\":\"MDEyMzQ1Njc=\"")
                .contains("\"droppedAttributesCount\":1")
                .doesNotContain("\"body\"");
    }

    // ---------- 辅助 ----------

    private static String envelopeFor(ResourceMetrics resourceMetrics, ScopeMetrics scopeMetrics,
                                      int metricIndex, Metric metric) {
        MetricDataPoint wrapper = new MetricDataPoint(new Batch<>(Context.root()), Context.root(), null);
        switch (metric.getDataCase()) {
            case HISTOGRAM -> wrapper.setFrom(1L, "b", metricIndex, resourceMetrics, scopeMetrics,
                    metric, metric.getHistogram(), 0, metric.getHistogram().getDataPoints(0));
            case EXPONENTIAL_HISTOGRAM -> wrapper.setFrom(1L, "b", metricIndex, resourceMetrics, scopeMetrics,
                    metric, metric.getExponentialHistogram(), 0, metric.getExponentialHistogram().getDataPoints(0));
            case SUMMARY -> wrapper.setFrom(1L, "b", metricIndex, resourceMetrics, scopeMetrics,
                    metric, metric.getSummary(), 0, metric.getSummary().getDataPoints(0));
            case GAUGE -> wrapper.setFrom(1L, "b", metricIndex, resourceMetrics, scopeMetrics,
                    metric, metric.getGauge(), 0, metric.getGauge().getDataPoints(0));
            default -> throw new IllegalStateException("unsupported: " + metric.getDataCase());
        }
        return OtlpJsonWriter.metricsEnvelope(wrapper);
    }

    private static KeyValue kv(String key, AnyValue value) {
        return KeyValue.newBuilder().setKey(key).setValue(value).build();
    }

    private static AnyValue str(String value) {
        return AnyValue.newBuilder().setStringValue(value).build();
    }
}

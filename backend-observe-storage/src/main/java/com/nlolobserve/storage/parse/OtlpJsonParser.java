package com.nlolobserve.storage.parse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.constant.AttributeKeys;
import com.nlolobserve.common.model.AnyValue;
import com.nlolobserve.common.model.InstrumentationScope;
import com.nlolobserve.common.model.KeyValue;
import com.nlolobserve.common.model.OtlpLogPayload;
import com.nlolobserve.common.model.OtlpMetricPayload;
import com.nlolobserve.common.model.OtlpTracePayload;
import com.nlolobserve.common.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OTLP/JSON → 行模型解析（详设 §1.3 parse/，T08）。
 * 输入为 T05 receiver OtlpJsonWriter 产出的单记录信封（Export*ServiceRequest 形态），
 * 按 common 模型（Otlp*Payload）反序列化：
 * - spans：提取 customer_id（resource 属性 nl.customer_id）/biz_code（span 属性 biz.code，
 *   缺省回退 name 前缀 biz.business.）/effective_duration_ms（span 属性 biz.effective_duration_ms），
 *   保留 resource/scope/attributes 为扁平 JSON 对象串（查询端 json_extract_path_text 取值）；
 *   traceId/spanId 原样入库（T05 已还原 SDK 原始 hex，不得再转码）。
 * - metrics：逐 metric 展开 data point（gauge/sum/histogram），计算 dp_hash（去重身份分量，
 *   含 serviceName 防同客户多 service 同值 data point 静默折叠）。
 * - logs：逐 log record 展开，计算 row_hash。
 * - spans events → events_json（T10 契约，DdlScripts.OTEL_SPANS 注释）：timeUnixNano→ISO-8601 UTC、
 *   attributes KeyValue 数组→扁平 map（与 attributes_json 同形态），无事件写 '[]'。
 * 哈希口径：SHA-256 hex，输入各字段长度前缀编码（T08 评审 F-4：内容含分隔符不可伪造字段边界），
 * 属性按 key 排序——同输入稳定、不同数据点区分。
 * 健壮性（T08 评审 F-1/F-2）：显式 JSON null 字段归一为空（不 NPE）；非法 JSON 抛
 * IllegalArgumentException。解析失败按记录粒度由仓库层隔离（单条坏载荷不毒化整批、
 * 不进重试路径），瞬态写库失败才由消费端重试/跳过（详设 §4.2）。
 */
@Component
public class OtlpJsonParser {

    private static final Logger LOG = LoggerFactory.getLogger(OtlpJsonParser.class);

    /** metric_type 列编码：OTLP MetricType 枚举值（详设 §3 列语义） */
    static final int METRIC_TYPE_GAUGE = 1;
    static final int METRIC_TYPE_SUM = 2;
    static final int METRIC_TYPE_HISTOGRAM = 3;

    /** 哈希字段分隔符（不可打印字符 0x1F，避免与属性内容歧义） */
    private static final char HASH_DELIMITER = '\u001f';

    private final ObjectMapper objectMapper;

    /**
     * Spring 装配入口：storage 无 spring-web（非 web 进程），JacksonAutoConfiguration 的
     * ObjectMapper bean 依赖 spring-web 的 Jackson2ObjectMapperBuilder 不生效，故自持实例。
     */
    public OtlpJsonParser() {
        this(new ObjectMapper());
    }

    public OtlpJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** ExportTraceServiceRequest JSON → otel_spans 行模型列表（显式 null 字段归一为空，F-2）。 */
    public List<SpanRow> parseSpans(String json) {
        OtlpTracePayload payload = read(OtlpTracePayload.class, json);
        List<SpanRow> rows = new ArrayList<>();
        if (payload == null) {
            return rows;
        }
        for (OtlpTracePayload.ResourceSpans resourceSpans : nullToEmpty(payload.getResourceSpans())) {
            Resource resource = resourceSpans.getResource() != null ? resourceSpans.getResource() : new Resource();
            String customerId = attributeAsString(resource, AttributeKeys.NL_CUSTOMER_ID);
            String serviceName = attributeAsString(resource, "service.name");
            String resourceJson = writeJson(flatAttributeMap(resource.getAttributes()));
            for (OtlpTracePayload.ScopeSpans scopeSpans : nullToEmpty(resourceSpans.getScopeSpans())) {
                String scopeJson = writeJson(scopeMap(scopeSpans.getScope()));
                for (OtlpTracePayload.Span span : nullToEmpty(scopeSpans.getSpans())) {
                    rows.add(toSpanRow(span, customerId, serviceName, resourceJson, scopeJson));
                }
            }
        }
        return rows;
    }

    /** ExportMetricsServiceRequest JSON → otel_metrics 行模型列表（逐 data point 展开）。 */
    public List<MetricRow> parseMetrics(String json) {
        OtlpMetricPayload payload = read(OtlpMetricPayload.class, json);
        List<MetricRow> rows = new ArrayList<>();
        if (payload == null) {
            return rows;
        }
        for (OtlpMetricPayload.ResourceMetrics resourceMetrics : nullToEmpty(payload.getResourceMetrics())) {
            Resource resource = resourceMetrics.getResource() != null ? resourceMetrics.getResource() : new Resource();
            String customerId = attributeAsString(resource, AttributeKeys.NL_CUSTOMER_ID);
            String serviceName = attributeAsString(resource, "service.name");
            String resourceJson = writeJson(flatAttributeMap(resource.getAttributes()));
            for (OtlpMetricPayload.ScopeMetrics scopeMetrics : nullToEmpty(resourceMetrics.getScopeMetrics())) {
                for (OtlpMetricPayload.Metric metric : nullToEmpty(scopeMetrics.getMetrics())) {
                    expandDataPoints(metric, customerId, serviceName, resourceJson, rows);
                }
            }
        }
        return rows;
    }

    /** ExportLogsServiceRequest JSON → otel_logs 行模型列表。 */
    public List<LogRow> parseLogs(String json) {
        OtlpLogPayload payload = read(OtlpLogPayload.class, json);
        List<LogRow> rows = new ArrayList<>();
        if (payload == null) {
            return rows;
        }
        for (OtlpLogPayload.ResourceLogs resourceLogs : nullToEmpty(payload.getResourceLogs())) {
            Resource resource = resourceLogs.getResource() != null ? resourceLogs.getResource() : new Resource();
            String customerId = attributeAsString(resource, AttributeKeys.NL_CUSTOMER_ID);
            String resourceJson = writeJson(flatAttributeMap(resource.getAttributes()));
            for (OtlpLogPayload.ScopeLogs scopeLogs : nullToEmpty(resourceLogs.getScopeLogs())) {
                for (OtlpLogPayload.LogRecord record : nullToEmpty(scopeLogs.getLogRecords())) {
                    rows.add(toLogRow(record, customerId, resourceJson));
                }
            }
        }
        return rows;
    }

    // ---------- spans ----------

    private SpanRow toSpanRow(OtlpTracePayload.Span span, String customerId, String serviceName,
                              String resourceJson, String scopeJson) {
        Map<String, Object> attrs = flatAttributeMap(span.getAttributes());
        String bizCode = stringOf(attrs.get(AttributeKeys.BIZ_CODE));
        if (bizCode.isEmpty() && span.getName() != null
                && span.getName().startsWith(AttributeKeys.BIZ_BUSINESS_NAME_PREFIX)) {
            bizCode = span.getName().substring(AttributeKeys.BIZ_BUSINESS_NAME_PREFIX.length());
        }
        OtlpTracePayload.Status status = span.getStatus();
        return new SpanRow(
                customerId,
                nullToEmpty(span.getTraceId()),
                nullToEmpty(span.getSpanId()),
                nullToEmpty(span.getParentSpanId()),
                nullToEmpty(span.getTraceState()),
                nullToEmpty(span.getName()),
                span.getKind(),
                nanoToInstant(span.getStartTimeUnixNano()),
                nanoToInstant(span.getEndTimeUnixNano()),
                status == null ? 0 : status.getCode(),
                status == null ? "" : nullToEmpty(status.getMessage()),
                bizCode,
                serviceName,
                doubleOf(attrs.get(AttributeKeys.BIZ_EFFECTIVE_DURATION_MS)),
                resourceJson,
                scopeJson,
                writeJson(attrs),
                eventsJson(span.getEvents()));
    }

    /**
     * span events → events_json（T10 契约，DdlScripts.OTEL_SPANS 注释）：查询端原生形态
     * [{"name","time":ISO-8601 UTC,"attributes":扁平键值}]；无事件写 '[]'（列缺省同值）。
     */
    private String eventsJson(List<OtlpTracePayload.SpanEvent> events) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (OtlpTracePayload.SpanEvent event : nullToEmpty(events)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", nullToEmpty(event.getName()));
            entry.put("time", nanoToInstant(event.getTimeUnixNano()).toString());
            entry.put("attributes", flatAttributeMap(event.getAttributes()));
            entries.add(entry);
        }
        return writeJson(entries);
    }

    // ---------- metrics ----------

    private void expandDataPoints(OtlpMetricPayload.Metric metric, String customerId, String serviceName,
                                  String resourceJson, List<MetricRow> rows) {
        boolean hasData = false;
        if (metric.getGauge() != null) {
            hasData = true;
            addDataPoints(metric, metric.getGauge().getDataPoints(), METRIC_TYPE_GAUGE,
                    customerId, serviceName, resourceJson, rows);
        }
        if (metric.getSum() != null) {
            hasData = true;
            addDataPoints(metric, metric.getSum().getDataPoints(), METRIC_TYPE_SUM,
                    customerId, serviceName, resourceJson, rows);
        }
        if (metric.getHistogram() != null) {
            hasData = true;
            addDataPoints(metric, metric.getHistogram().getDataPoints(), METRIC_TYPE_HISTOGRAM,
                    customerId, serviceName, resourceJson, rows);
        }
        if (!hasData) {
            // receiver OtlpJsonWriter 可能写出 exponentialHistogram/summary（common 模型未建模，
            // ignoreUnknown 静默丢弃）——不产生行时留痕，防数据无声消失（T08 评审 P2-1）
            LOG.warn("metric {} 无已知 data point 形态（gauge/sum/histogram 均空——"
                    + "exponentialHistogram/summary 未建模），跳过不产生行", metric.getName());
        }
    }

    private void addDataPoints(OtlpMetricPayload.Metric metric, List<OtlpMetricPayload.DataPoint> dataPoints,
                               int metricType, String customerId, String serviceName,
                               String resourceJson, List<MetricRow> rows) {
        for (OtlpMetricPayload.DataPoint point : nullToEmpty(dataPoints)) {
            Map<String, Object> attrs = flatAttributeMap(point.getAttributes());
            String timeNano = firstNonBlank(point.getTimeUnixNano(), point.getStartTimeUnixNano());
            Double value = dataPointValue(point);
            String attributesJson = writeJson(attrs);
            // dp_hash 含 serviceName：同客户下多 service 的相同 data point 不互折叠（T08 评审 Minor）
            String dpHash = sha256Hex(String.join(String.valueOf(HASH_DELIMITER),
                    encodeHashField(customerId), encodeHashField(serviceName),
                    encodeHashField(nullToEmpty(metric.getName())), encodeHashField(timeNano),
                    encodeHashField(value == null ? "" : String.valueOf(value)),
                    encodeHashField(canonicalAttributes(attrs))));
            rows.add(new MetricRow(
                    customerId,
                    nullToEmpty(metric.getName()),
                    metricType,
                    nanoToInstant(timeNano),
                    value,
                    stringOf(attrs.get(AttributeKeys.BIZ_CODE)),
                    resourceJson,
                    attributesJson,
                    dpHash));
        }
    }

    private static Double dataPointValue(OtlpMetricPayload.DataPoint point) {
        if (point.getAsDouble() != null) {
            return point.getAsDouble();
        }
        if (point.getAsInt() != null && !point.getAsInt().isBlank()) {
            return Double.parseDouble(point.getAsInt());
        }
        return null;
    }

    // ---------- logs ----------

    private LogRow toLogRow(OtlpLogPayload.LogRecord record, String customerId, String resourceJson) {
        Map<String, Object> attrs = flatAttributeMap(record.getAttributes());
        String timeNano = firstNonBlank(record.getTimeUnixNano(), record.getObservedTimeUnixNano());
        String body = anyValueToString(record.getBody());
        String attributesJson = writeJson(attrs);
        String rowHash = sha256Hex(String.join(String.valueOf(HASH_DELIMITER),
                encodeHashField(customerId), encodeHashField(nullToEmpty(record.getTraceId())),
                encodeHashField(nullToEmpty(record.getSpanId())), encodeHashField(timeNano),
                encodeHashField(String.valueOf(record.getSeverityNumber())),
                encodeHashField(nullToEmpty(record.getSeverityText())),
                encodeHashField(body), encodeHashField(canonicalAttributes(attrs))));
        return new LogRow(
                customerId,
                nullToEmpty(record.getTraceId()),
                nullToEmpty(record.getSpanId()),
                nanoToInstant(timeNano),
                record.getSeverityNumber(),
                nullToEmpty(record.getSeverityText()),
                body,
                resourceJson,
                attributesJson,
                rowHash);
    }

    // ---------- 公共：属性展平 / JSON / 哈希 / 时间 ----------

    /** resource 属性取值（仅 nl.customer_id/service.name 等字符串语义字段）。 */
    private static String attributeAsString(Resource resource, String key) {
        return stringOf(flatAttributeMap(resource.getAttributes()).get(key));
    }

    /** KeyValue 列表 → 扁平 map（key 重复后写覆盖；查询端 json_extract_path_text 依赖该形态）。
     *  null 列表/null 条目/null key 归一跳过（F-2/P3-7：防 NPE 落入毒丸路径）。 */
    private static Map<String, Object> flatAttributeMap(List<KeyValue> attributes) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (KeyValue kv : nullToEmpty(attributes)) {
            if (kv == null || kv.getKey() == null) {
                continue;
            }
            map.put(kv.getKey(), anyValueToJsonObject(kv.getValue()));
        }
        return map;
    }

    private static Map<String, Object> scopeMap(InstrumentationScope scope) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (scope == null) {
            return map;
        }
        if (scope.getName() != null) {
            map.put("name", scope.getName());
        }
        if (scope.getVersion() != null) {
            map.put("version", scope.getVersion());
        }
        return map;
    }

    /** AnyValue → JSON 可序列化对象（OTLP/JSON 规范：int64 为字符串，此处还原为数值保查询语义）。 */
    private static Object anyValueToJsonObject(AnyValue value) {
        if (value == null) {
            return "";
        }
        if (value.getStringValue() != null) {
            return value.getStringValue();
        }
        if (value.getIntValue() != null) {
            return Long.parseLong(value.getIntValue());
        }
        if (value.getDoubleValue() != null) {
            return value.getDoubleValue();
        }
        if (value.getBoolValue() != null) {
            return value.getBoolValue();
        }
        if (value.getBytesValue() != null) {
            return value.getBytesValue();
        }
        return "";
    }

    /** AnyValue → 文本（log body 等单值语义字段）。 */
    private static String anyValueToString(AnyValue value) {
        Object json = anyValueToJsonObject(value);
        return json instanceof String ? (String) json : String.valueOf(json);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 字段序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 属性规范化串（按 key 排序，k=v 以分隔符连接）——dp_hash/row_hash 稳定性关键。
     * key/value 均长度前缀编码（T08 评审 F-4）：内容含 '='/分隔符时不可伪造相邻字段边界
     * （反例 {a:"b\u001fc=d"} 与 {a:"b",c:"d"} 旧口径规范化串相同 → 可构造哈希碰撞劫持他行）。
     */
    private static String canonicalAttributes(Map<String, Object> attrs) {
        Map<String, Object> sorted = new TreeMap<>(attrs);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(HASH_DELIMITER);
            }
            sb.append(encodeHashField(entry.getKey())).append('=').append(encodeHashField(stringOf(entry.getValue())));
        }
        return sb.toString();
    }

    /** 哈希字段长度前缀编码（"len:field"）：字段边界自定界，内容不可伪造分隔语义。 */
    private static String encodeHashField(String field) {
        String s = field == null ? "" : field;
        return s.length() + ":" + s;
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用（JVM 规范算法）", e);
        }
    }

    /**
     * uint64 纳秒字符串 → Instant；空/非法回退 epoch 0（列 NOT NULL，不得为 null）。
     * 回退必留痕（T08 评审 Minor/P2-5：epoch 行落在区间查询外且参与去重键，静默回退致坏数据不可发现）。
     */
    private static Instant nanoToInstant(String timeUnixNano) {
        if (timeUnixNano == null || timeUnixNano.isBlank()) {
            LOG.warn("timeUnixNano 缺失：回退 epoch 0（去重键 time 分量受影响，行可经 1970 区间定位）");
            return Instant.EPOCH;
        }
        try {
            long nanos = Long.parseLong(timeUnixNano.trim());
            return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L),
                    Math.floorMod(nanos, 1_000_000_000L));
        } catch (NumberFormatException e) {
            LOG.warn("timeUnixNano 非法（非 uint64 数值）：回退 epoch 0，raw={}", timeUnixNano);
            return Instant.EPOCH;
        }
    }

    private <T> T read(Class<T> type, String json) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("OTLP/JSON 载荷解析失败: " + e.getMessage(), e);
        }
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double doubleOf(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : nullToEmpty(second);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 显式 null 列表归一为空列表（F-2：Jackson 对 JSON 显式 null 会置 null，覆盖模型默认值）。 */
    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}

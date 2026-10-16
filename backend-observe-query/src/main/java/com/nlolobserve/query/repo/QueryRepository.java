package com.nlolobserve.query.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.constant.AttributeKeys;
import com.nlolobserve.common.ddl.Columns;
import com.nlolobserve.common.ddl.TableNames;
import com.nlolobserve.common.dto.BizMetric;
import com.nlolobserve.common.dto.BusinessItem;
import com.nlolobserve.common.dto.SpanDetail;
import com.nlolobserve.common.dto.SpanNode;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * 查询端数据访问（详设 §1.4 repo 层）：仅经 NamedParameterJdbcTemplate 命名参数查询，
 * 禁止字符串拼接（安全评审 A03 参数化）。所有 SQL 为静态模板常量（公开可见，供单测断言占位符）。
 *
 * Databend 方言约定：
 * - 查询带 {@code FINAL}（Replacing 去重身份键折叠，详设 §3/§4.3）；
 * - 可选过滤项用空串哨兵 {@code ('' = :param OR col = :param)}：调用方 null 归一为空串，
 *   避免 Databend 对 NULL 类型参数的推导问题，同时保持单模板 + 纯命名参数；
 * - 时间范围：fromInclusive = from 当日 0 点（UTC），toExclusive = to 次日 0 点（UTC）。
 */
@Repository
public class QueryRepository {

    /** 业务列表上限（详设 §2.1：按 start_time 倒序，LIMIT 200 上限） */
    public static final int BUSINESS_LIST_LIMIT = 200;

    /** 命名参数键：与 SQL 模板 :customerId 占位符一致（列名常量只用于 SQL 文本，不作绑定键） */
    public static final String PARAM_CUSTOMER_ID = "customerId";
    /** 命名参数键：与 SQL 模板 :traceId 占位符一致 */
    public static final String PARAM_TRACE_ID = "traceId";
    /** 命名参数键：与 SQL 模板 :spanId 占位符一致 */
    public static final String PARAM_SPAN_ID = "spanId";

    /**
     * 业务列表（详设 §2.1）：业务 root span（name LIKE 'biz.business.%'），
     * status/endReason 取自 attributes_json 的 biz.status / biz.end_reason。
     */
    /** Databend 方言：JSON 取值用 json_extract_path_text（json_extract_string 是 ClickHouse
     *  函数，Databend 无此函数——T09 评审 Critical，IT 实跑复现 QueryError 1008） */
    public static final String JSON_EXTRACT_FUNC = "json_extract_path_text";

    public static final String SQL_BUSINESS_ITEMS =
            "SELECT " + Columns.SPANS_SPAN_ID + ", " + Columns.SPANS_BIZ_CODE + ", "
                    + Columns.SPANS_TRACE_ID + ", " + Columns.SPANS_START_TIME + ", "
                    + Columns.SPANS_EFFECTIVE_DURATION_MS + ","
                    + " " + JSON_EXTRACT_FUNC + "(" + Columns.SPANS_ATTRIBUTES_JSON
                    + ", '$.\"biz.status\"') AS biz_status,"
                    + " " + JSON_EXTRACT_FUNC + "(" + Columns.SPANS_ATTRIBUTES_JSON
                    + ", '$.\"biz.end_reason\"') AS biz_end_reason"
                    + " FROM " + TableNames.OTEL_SPANS + " FINAL"
                    + " WHERE " + Columns.SPANS_CUSTOMER_ID + " = :customerId"
                    + " AND " + Columns.SPANS_NAME + " LIKE :bizNamePrefix"
                    + " AND " + Columns.SPANS_START_TIME + " >= :fromInclusive"
                    + " AND " + Columns.SPANS_START_TIME + " < :toExclusive"
                    + " AND ('' = :bizCode OR " + Columns.SPANS_BIZ_CODE + " = :bizCode)"
                    + " ORDER BY " + Columns.SPANS_START_TIME + " DESC"
                    + " LIMIT " + BUSINESS_LIST_LIMIT;

    /** trace 全 span 行数上限（安全评审 F6：超大 trace 无界结果集读入内存的 DoS 面） */
    public static final int SPANS_BY_TRACE_LIMIT = 10000;

    /** trace 全 span（详设 §2.2），按 start_time 正序 */
    public static final String SQL_SPANS_BY_TRACE =
            "SELECT " + Columns.SPANS_SPAN_ID + ", " + Columns.SPANS_PARENT_SPAN_ID + ", "
                    + Columns.SPANS_NAME + ", " + Columns.SPANS_KIND + ", "
                    + Columns.SPANS_START_TIME + ", " + Columns.SPANS_END_TIME + ", "
                    + Columns.SPANS_STATUS_CODE + ", " + Columns.SPANS_EFFECTIVE_DURATION_MS + ", "
                    + Columns.SPANS_ATTRIBUTES_JSON + ", " + Columns.SPANS_EVENTS_JSON
                    + " FROM " + TableNames.OTEL_SPANS + " FINAL"
                    + " WHERE " + Columns.SPANS_CUSTOMER_ID + " = :customerId"
                    + " AND " + Columns.SPANS_TRACE_ID + " = :traceId"
                    + " ORDER BY " + Columns.SPANS_START_TIME
                    + " LIMIT " + SPANS_BY_TRACE_LIMIT;

    /** 单 span 全字段（详设 §2.3；resource_json/scope_json/attributes_json 解析为对象） */
    public static final String SQL_SPAN_DETAIL =
            "SELECT " + Columns.SPANS_TRACE_ID + ", " + Columns.SPANS_SPAN_ID + ", "
                    + Columns.SPANS_PARENT_SPAN_ID + ", " + Columns.SPANS_TRACE_STATE + ", "
                    + Columns.SPANS_NAME + ", " + Columns.SPANS_KIND + ", "
                    + Columns.SPANS_START_TIME + ", " + Columns.SPANS_END_TIME + ", "
                    + Columns.SPANS_STATUS_CODE + ", " + Columns.SPANS_STATUS_MESSAGE + ", "
                    + Columns.SPANS_BIZ_CODE + ", " + Columns.SPANS_SERVICE_NAME + ", "
                    + Columns.SPANS_EFFECTIVE_DURATION_MS + ", " + Columns.SPANS_RESOURCE_JSON + ", "
                    + Columns.SPANS_SCOPE_JSON + ", " + Columns.SPANS_ATTRIBUTES_JSON
                    + ", " + Columns.SPANS_EVENTS_JSON
                    + " FROM " + TableNames.OTEL_SPANS + " FINAL"
                    + " WHERE " + Columns.SPANS_CUSTOMER_ID + " = :customerId"
                    + " AND " + Columns.SPANS_TRACE_ID + " = :traceId"
                    + " AND " + Columns.SPANS_SPAN_ID + " = :spanId";

    /** 首屏加载 P95（详设 §2.4 firstScreenLoadP95）；customerId 空串=跨客户运维视图 */
    public static final String SQL_FIRST_SCREEN_LOAD_P95 =
            "SELECT quantile(0.95)(" + Columns.METRICS_VALUE + ") AS p95"
                    + " FROM " + TableNames.OTEL_METRICS + " FINAL"
                    + " WHERE " + Columns.METRICS_METRIC_NAME + " = :metricName"
                    + " AND " + Columns.METRICS_TIME + " >= :fromInclusive"
                    + " AND " + Columns.METRICS_TIME + " < :toExclusive"
                    + " AND ('' = :customerId OR " + Columns.METRICS_CUSTOMER_ID + " = :customerId)";

    /**
     * 分业务汇总（详设 §2.4 perBiz）：业务 root span 的 effective_duration_ms 分位与成功率。
     * successRate 由 success_count / span_count 在查询端计算（biz.status 白名单 success）。
     */
    public static final String SQL_PER_BIZ_SUMMARY =
            "SELECT " + Columns.SPANS_BIZ_CODE + ","
                    + " count(*) AS span_count,"
                    + " quantile(0.95)(" + Columns.SPANS_EFFECTIVE_DURATION_MS + ") AS effective_duration_p95,"
                    + " sum(if(" + JSON_EXTRACT_FUNC + "(" + Columns.SPANS_ATTRIBUTES_JSON
                    + ", '$.\"biz.status\"') = 'success', 1, 0)) AS success_count"
                    + " FROM " + TableNames.OTEL_SPANS + " FINAL"
                    + " WHERE " + Columns.SPANS_NAME + " LIKE :bizNamePrefix"
                    + " AND " + Columns.SPANS_START_TIME + " >= :fromInclusive"
                    + " AND " + Columns.SPANS_START_TIME + " < :toExclusive"
                    + " AND ('' = :customerId OR " + Columns.SPANS_CUSTOMER_ID + " = :customerId)"
                    + " AND ('' = :bizCode OR " + Columns.SPANS_BIZ_CODE + " = :bizCode)"
                    + " GROUP BY " + Columns.SPANS_BIZ_CODE
                    + " ORDER BY " + Columns.SPANS_BIZ_CODE;

    static final RowMapper<BusinessItem> BUSINESS_ITEM_MAPPER = QueryRepository::mapBusinessItem;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<List<Map<String, Object>>> EVENT_LIST_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;

    public QueryRepository(NamedParameterJdbcTemplate namedJdbc, ObjectMapper objectMapper) {
        this.namedJdbc = namedJdbc;
        this.objectMapper = objectMapper;
    }

    /** 业务列表（详设 §2.1）。bizCode 为空串/ null 表示不过滤（模板哨兵语义）。 */
    public List<BusinessItem> findBusinessItems(String customerId, LocalDate from, LocalDate to, String bizCode) {
        SqlParameterSource params = spanRangeParams(customerId, from, to)
                .addValue("bizCode", nullToEmpty(bizCode));
        return namedJdbc.query(SQL_BUSINESS_ITEMS, params, BUSINESS_ITEM_MAPPER);
    }

    /** trace 全 span（详设 §2.2）。 */
    public List<SpanNode> findSpans(String customerId, String traceId) {
        return namedJdbc.query(SQL_SPANS_BY_TRACE, traceParams(customerId, traceId), this::mapSpanNode);
    }

    /** 单 span 全字段（详设 §2.3），无数据返回 empty */
    public Optional<SpanDetail> findSpan(String customerId, String traceId, String spanId) {
        List<SpanDetail> rows = namedJdbc.query(SQL_SPAN_DETAIL,
                traceParams(customerId, traceId).addValue(PARAM_SPAN_ID, spanId),
                this::mapSpanDetail);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 首屏加载 P95（详设 §2.4）；customerId 空串/ null=跨客户运维视图；窗口内无数据点返回 null */
    public Double findFirstScreenLoadP95(LocalDate from, LocalDate to, String customerId) {
        SqlParameterSource params = metricRangeParams(from, to)
                .addValue("metricName", AttributeKeys.NL_FIRST_SCREEN_LOAD)
                .addValue("customerId", nullToEmpty(customerId));
        List<Double> rows = namedJdbc.queryForList(SQL_FIRST_SCREEN_LOAD_P95, params, Double.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 分业务汇总（详设 §2.4 perBiz）。customerId/bizCode 空串/ null 表示不过滤。
     *  参数序与其余方法一致（customerId 在前，评审 m5）。 */
    public List<BizMetric> findPerBizMetrics(LocalDate from, LocalDate to, String customerId, String bizCode) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("bizNamePrefix", AttributeKeys.BIZ_BUSINESS_NAME_PREFIX + "%")
                .addValue("fromInclusive", boundaryTimestamp(from, false))
                .addValue("toExclusive", boundaryTimestamp(to, true))
                .addValue(PARAM_CUSTOMER_ID, nullToEmpty(customerId))
                .addValue("bizCode", nullToEmpty(bizCode));
        return namedJdbc.query(SQL_PER_BIZ_SUMMARY, params, this::mapPerBizMetric);
    }

    private static MapSqlParameterSource spanRangeParams(String customerId, LocalDate from, LocalDate to) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (customerId != null) {
            params.addValue(PARAM_CUSTOMER_ID, customerId);
        }
        return params
                .addValue("bizNamePrefix", AttributeKeys.BIZ_BUSINESS_NAME_PREFIX + "%")
                .addValue("fromInclusive", boundaryTimestamp(from, false))
                .addValue("toExclusive", boundaryTimestamp(to, true));
    }

    private static MapSqlParameterSource metricRangeParams(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("fromInclusive", boundaryTimestamp(from, false))
                .addValue("toExclusive", boundaryTimestamp(to, true));
    }

    private static MapSqlParameterSource traceParams(String customerId, String traceId) {
        return new MapSqlParameterSource()
                .addValue(PARAM_CUSTOMER_ID, customerId)
                .addValue(PARAM_TRACE_ID, traceId);
    }

    /** 时间边界统一 UTC（Databend TIMESTAMP 无时区，写入侧同为 UTC，详设 §3） */
    private static Timestamp boundaryTimestamp(LocalDate date, boolean exclusive) {
        LocalDate day = exclusive ? date.plusDays(1) : date;
        return Timestamp.from(day.atStartOfDay().atZone(ZoneOffset.UTC).toInstant());
    }

    private static BusinessItem mapBusinessItem(ResultSet rs, int rowNum) throws SQLException {
        BusinessItem item = new BusinessItem();
        item.setBizId(rs.getString(Columns.SPANS_SPAN_ID));
        item.setBizCode(rs.getString(Columns.SPANS_BIZ_CODE));
        item.setTraceId(rs.getString(Columns.SPANS_TRACE_ID));
        item.setStartTime(toIsoUtc(rs.getTimestamp(Columns.SPANS_START_TIME)));
        item.setEffectiveDurationMs(rs.getDouble(Columns.SPANS_EFFECTIVE_DURATION_MS));
        item.setStatus(rs.getString("biz_status"));
        item.setEndReason(rs.getString("biz_end_reason"));
        return item;
    }

    private SpanNode mapSpanNode(ResultSet rs, int rowNum) throws SQLException {
        SpanNode node = new SpanNode();
        node.setSpanId(rs.getString(Columns.SPANS_SPAN_ID));
        node.setParentSpanId(rs.getString(Columns.SPANS_PARENT_SPAN_ID));
        node.setName(rs.getString(Columns.SPANS_NAME));
        node.setKind(rs.getInt(Columns.SPANS_KIND));
        Timestamp start = rs.getTimestamp(Columns.SPANS_START_TIME);
        Timestamp end = rs.getTimestamp(Columns.SPANS_END_TIME);
        node.setStartTime(toIsoUtc(start));
        node.setEndTime(toIsoUtc(end));
        node.setStatusCode(rs.getInt(Columns.SPANS_STATUS_CODE));
        if (start != null && end != null) {
            node.setDurationMs(end.getTime() - start.getTime());
        }
        node.setEffectiveDurationMs(rs.getDouble(Columns.SPANS_EFFECTIVE_DURATION_MS));
        node.setAttributes(parseJsonMap(rs.getString(Columns.SPANS_ATTRIBUTES_JSON)));
        node.setEvents(parseEvents(rs.getString(Columns.SPANS_EVENTS_JSON)));
        return node;
    }

    private SpanDetail mapSpanDetail(ResultSet rs, int rowNum) throws SQLException {
        SpanDetail detail = new SpanDetail();
        detail.setTraceId(rs.getString(Columns.SPANS_TRACE_ID));
        detail.setSpanId(rs.getString(Columns.SPANS_SPAN_ID));
        detail.setParentSpanId(rs.getString(Columns.SPANS_PARENT_SPAN_ID));
        detail.setTraceState(rs.getString(Columns.SPANS_TRACE_STATE));
        detail.setName(rs.getString(Columns.SPANS_NAME));
        detail.setKind(rs.getInt(Columns.SPANS_KIND));
        Timestamp start = rs.getTimestamp(Columns.SPANS_START_TIME);
        Timestamp end = rs.getTimestamp(Columns.SPANS_END_TIME);
        detail.setStartTime(toIsoUtc(start));
        detail.setEndTime(toIsoUtc(end));
        detail.setStatusCode(rs.getInt(Columns.SPANS_STATUS_CODE));
        detail.setStatusMessage(rs.getString(Columns.SPANS_STATUS_MESSAGE));
        detail.setBizCode(rs.getString(Columns.SPANS_BIZ_CODE));
        detail.setServiceName(rs.getString(Columns.SPANS_SERVICE_NAME));
        if (start != null && end != null) {
            detail.setDurationMs(end.getTime() - start.getTime());
        }
        detail.setEffectiveDurationMs(rs.getDouble(Columns.SPANS_EFFECTIVE_DURATION_MS));
        detail.setResource(parseJsonMap(rs.getString(Columns.SPANS_RESOURCE_JSON)));
        detail.setScope(parseJsonMap(rs.getString(Columns.SPANS_SCOPE_JSON)));
        detail.setAttributes(parseJsonMap(rs.getString(Columns.SPANS_ATTRIBUTES_JSON)));
        detail.setEvents(parseEvents(rs.getString(Columns.SPANS_EVENTS_JSON)));
        return detail;
    }

    private BizMetric mapPerBizMetric(ResultSet rs, int rowNum) throws SQLException {
        BizMetric metric = new BizMetric();
        metric.setBizCode(rs.getString(Columns.SPANS_BIZ_CODE));
        metric.setEffectiveDurationP95(rs.getDouble("effective_duration_p95"));
        long spanCount = rs.getLong("span_count");
        metric.setCount(spanCount);
        double successRate = spanCount == 0 ? 0.0 : rs.getDouble("success_count") / (double) spanCount;
        metric.setSuccessRate(successRate);
        return metric;
    }

    private Map<String, Object> parseJsonMap(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("JSON 字段解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * events_json → EventNode 列表（契约见 DdlScripts.OTEL_SPANS 注释：
     * [{"name","time":ISO-8601 UTC,"attributes":扁平键值}]，存储写路径已从
     * OTLP 形态转换）。null/空白/[] → 空列表（列缺省 DEFAULT '[]'）。
     */
    private List<SpanNode.EventNode> parseEvents(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> raw;
        try {
            raw = objectMapper.readValue(json, EVENT_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("events_json 解析失败: " + e.getMessage(), e);
        }
        List<SpanNode.EventNode> events = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            SpanNode.EventNode event = new SpanNode.EventNode();
            Object name = entry.get("name");
            Object time = entry.get("time");
            Object attributes = entry.get("attributes");
            event.setName(name == null ? "" : String.valueOf(name));
            event.setTime(time == null ? null : String.valueOf(time));
            if (attributes instanceof Map<?, ?> attrMap) {
                Map<String, Object> flat = new HashMap<>(attrMap.size());
                for (Map.Entry<?, ?> e : attrMap.entrySet()) {
                    flat.put(String.valueOf(e.getKey()), e.getValue());
                }
                event.setAttributes(flat);
            }
            events.add(event);
        }
        return events;
    }

    private static String toIsoUtc(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochMilli(timestamp.getTime()).toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

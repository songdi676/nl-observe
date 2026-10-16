package com.nlolobserve.storage.repo;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import com.nlolobserve.common.ddl.Columns;
import com.nlolobserve.common.ddl.TableNames;
import com.nlolobserve.storage.parse.OtlpJsonParser;
import com.nlolobserve.storage.parse.SpanRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * otel_spans 写入仓库（详设 §1.3 repo/，T08 落地）：
 * OTLP/JSON 载荷 → {@link OtlpJsonParser} 解析为 {@link SpanRow} →
 * 静态 REPLACE INTO 模板 + 命名参数按 500/批写入。
 * 去重身份 = 聚簇键 (customer_id, trace_id, span_id, start_time, end_time)：
 * 同 span 重复上报（Kafka 重投/多实例）经 REPLACE INTO 折叠，不产生重复行（详设 §3/§4.2）。
 * Databend 方言（v1.2.881 实测）：REPLACE INTO 必须显式 {@code ON (去重键)} 子句
 * （键 = 聚簇键），否则解析失败；ON 键上的重复行被整行替换。
 * 写入失败抛异常由消费端重试/跳过。
 */
@Repository
public class JdbcSpansRepository implements SpansRepository {

    /** 去重身份键 = 聚簇键（DdlScripts OTEL_SPANS CLUSTER BY，详设 §3） */
    static final String DEDUP_KEY = "(" + Columns.SPANS_CUSTOMER_ID + ", "
            + Columns.SPANS_TRACE_ID + ", " + Columns.SPANS_SPAN_ID + ", "
            + Columns.SPANS_START_TIME + ", " + Columns.SPANS_END_TIME + ")";

    static final String REPLACE_INTO_SQL = "REPLACE INTO " + TableNames.OTEL_SPANS + " ("
            + Columns.SPANS_CUSTOMER_ID + ", "
            + Columns.SPANS_TRACE_ID + ", "
            + Columns.SPANS_SPAN_ID + ", "
            + Columns.SPANS_PARENT_SPAN_ID + ", "
            + Columns.SPANS_TRACE_STATE + ", "
            + Columns.SPANS_NAME + ", "
            + Columns.SPANS_KIND + ", "
            + Columns.SPANS_START_TIME + ", "
            + Columns.SPANS_END_TIME + ", "
            + Columns.SPANS_STATUS_CODE + ", "
            + Columns.SPANS_STATUS_MESSAGE + ", "
            + Columns.SPANS_BIZ_CODE + ", "
            + Columns.SPANS_SERVICE_NAME + ", "
            + Columns.SPANS_EFFECTIVE_DURATION_MS + ", "
            + Columns.SPANS_RESOURCE_JSON + ", "
            + Columns.SPANS_SCOPE_JSON + ", "
            + Columns.SPANS_ATTRIBUTES_JSON
            + ") ON " + DEDUP_KEY + " VALUES ("
            + ":customerId, :traceId, :spanId, :parentSpanId, :traceState, :name, :kind, "
            + ":startTime, :endTime, :statusCode, :statusMessage, :bizCode, :serviceName, "
            + ":effectiveDurationMs, :resourceJson, :scopeJson, :attributesJson)";

    private final NamedParameterJdbcTemplate namedJdbc;
    private final OtlpJsonParser parser;

    public JdbcSpansRepository(NamedParameterJdbcTemplate namedJdbc, OtlpJsonParser parser) {
        this.namedJdbc = namedJdbc;
        this.parser = parser;
    }

    @Override
    public void write(List<String> payloads) {
        List<SpanRow> rows = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            rows.addAll(parser.parseSpans(payload));
        }
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] sources = new SqlParameterSource[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            sources[i] = toParamSource(rows.get(i));
        }
        JdbcBatchWriteSupport.chunkedBatchUpdate(namedJdbc, REPLACE_INTO_SQL, sources);
    }

    private static MapSqlParameterSource toParamSource(SpanRow row) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("customerId", row.customerId());
        params.addValue("traceId", row.traceId());
        params.addValue("spanId", row.spanId());
        params.addValue("parentSpanId", row.parentSpanId());
        params.addValue("traceState", row.traceState());
        params.addValue("name", row.name());
        params.addValue("kind", row.kind());
        params.addValue("startTime", Timestamp.from(row.startTime()));
        params.addValue("endTime", Timestamp.from(row.endTime()));
        params.addValue("statusCode", row.statusCode());
        params.addValue("statusMessage", row.statusMessage());
        params.addValue("bizCode", row.bizCode());
        params.addValue("serviceName", row.serviceName());
        params.addValue("effectiveDurationMs", row.effectiveDurationMs());
        params.addValue("resourceJson", row.resourceJson());
        params.addValue("scopeJson", row.scopeJson());
        params.addValue("attributesJson", row.attributesJson());
        return params;
    }
}

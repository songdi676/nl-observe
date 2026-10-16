package com.nlolobserve.storage.repo;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import com.nlolobserve.common.ddl.Columns;
import com.nlolobserve.common.ddl.TableNames;
import com.nlolobserve.storage.parse.LogRow;
import com.nlolobserve.storage.parse.OtlpJsonParser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * otel_logs 写入仓库（详设 §1.3 repo/，T08 落地）：
 * OTLP/JSON 载荷 → {@link OtlpJsonParser} 展开 log record 为 {@link LogRow}
 * （row_hash 稳定标识同一记录）→ 静态 REPLACE INTO 模板 + 命名参数按 500/批写入。
 * 去重身份 = 聚簇键 (customer_id, trace_id, time, row_hash)：同记录重复上报折叠（详设 §3）。
 * Databend 方言（v1.2.881 实测）：REPLACE INTO 必须显式 {@code ON (去重键)} 子句
 * （键 = 聚簇键），否则解析失败；ON 键上的重复行被整行替换。
 * 写入失败抛异常由消费端重试/跳过。
 */
@Repository
public class JdbcLogsRepository implements LogsRepository {

    /** 去重身份键 = 聚簇键（DdlScripts OTEL_LOGS CLUSTER BY，详设 §3） */
    static final String DEDUP_KEY = "(" + Columns.LOGS_CUSTOMER_ID + ", "
            + Columns.LOGS_TRACE_ID + ", " + Columns.LOGS_TIME + ", "
            + Columns.LOGS_ROW_HASH + ")";

    static final String REPLACE_INTO_SQL = "REPLACE INTO " + TableNames.OTEL_LOGS + " ("
            + Columns.LOGS_CUSTOMER_ID + ", "
            + Columns.LOGS_TRACE_ID + ", "
            + Columns.LOGS_SPAN_ID + ", "
            + Columns.LOGS_TIME + ", "
            + Columns.LOGS_SEVERITY_NUMBER + ", "
            + Columns.LOGS_SEVERITY_TEXT + ", "
            + Columns.LOGS_BODY + ", "
            + Columns.LOGS_RESOURCE_JSON + ", "
            + Columns.LOGS_ATTRIBUTES_JSON + ", "
            + Columns.LOGS_ROW_HASH
            + ") ON " + DEDUP_KEY + " VALUES ("
            + ":customerId, :traceId, :spanId, :time, :severityNumber, :severityText, :body, "
            + ":resourceJson, :attributesJson, :rowHash)";

    private final NamedParameterJdbcTemplate namedJdbc;
    private final OtlpJsonParser parser;

    public JdbcLogsRepository(NamedParameterJdbcTemplate namedJdbc, OtlpJsonParser parser) {
        this.namedJdbc = namedJdbc;
        this.parser = parser;
    }

    @Override
    public void write(List<String> payloads) {
        List<LogRow> rows = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            rows.addAll(parser.parseLogs(payload));
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

    private static MapSqlParameterSource toParamSource(LogRow row) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("customerId", row.customerId());
        params.addValue("traceId", row.traceId());
        params.addValue("spanId", row.spanId());
        params.addValue("time", Timestamp.from(row.time()));
        params.addValue("severityNumber", row.severityNumber());
        params.addValue("severityText", row.severityText());
        params.addValue("body", row.body());
        params.addValue("resourceJson", row.resourceJson());
        params.addValue("attributesJson", row.attributesJson());
        params.addValue("rowHash", row.rowHash());
        return params;
    }
}

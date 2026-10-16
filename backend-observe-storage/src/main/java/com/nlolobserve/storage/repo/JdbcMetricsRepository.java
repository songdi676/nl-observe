package com.nlolobserve.storage.repo;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import com.nlolobserve.common.ddl.Columns;
import com.nlolobserve.common.ddl.TableNames;
import com.nlolobserve.storage.parse.MetricRow;
import com.nlolobserve.storage.parse.OtlpJsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * otel_metrics 写入仓库（详设 §1.3 repo/，T08 落地）：
 * OTLP/JSON 载荷 → {@link OtlpJsonParser} 展开 data point 为 {@link MetricRow}
 * （dp_hash 稳定标识同一数据点）→ 静态 REPLACE INTO 模板 + 命名参数按 500/批写入。
 * 去重身份 = 聚簇键 (customer_id, metric_name, time, dp_hash)：不同数据点互不折叠，
 * 同一 data point 重复上报折叠（详设 §3）。
 * Databend 方言（v1.2.881 实测）：REPLACE INTO 必须显式 {@code ON (去重键)} 子句
 * （键 = 聚簇键），否则解析失败；ON 键上的重复行被整行替换。
 * 失败语义（T08 评审 F-1）：解析失败按记录粒度隔离（error 日志 + 跳过该条）；仅写库失败
 * 抛异常由消费端重试/跳过。
 */
@Repository
public class JdbcMetricsRepository implements MetricsRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcMetricsRepository.class);

    /** 去重身份键 = 聚簇键（DdlScripts OTEL_METRICS CLUSTER BY，详设 §3） */
    static final String DEDUP_KEY = "(" + Columns.METRICS_CUSTOMER_ID + ", "
            + Columns.METRICS_METRIC_NAME + ", " + Columns.METRICS_TIME + ", "
            + Columns.METRICS_DP_HASH + ")";

    static final String REPLACE_INTO_SQL = "REPLACE INTO " + TableNames.OTEL_METRICS + " ("
            + Columns.METRICS_CUSTOMER_ID + ", "
            + Columns.METRICS_METRIC_NAME + ", "
            + Columns.METRICS_METRIC_TYPE + ", "
            + Columns.METRICS_TIME + ", "
            + Columns.METRICS_VALUE + ", "
            + Columns.METRICS_BIZ_CODE + ", "
            + Columns.METRICS_RESOURCE_JSON + ", "
            + Columns.METRICS_ATTRIBUTES_JSON + ", "
            + Columns.METRICS_DP_HASH
            + ") ON " + DEDUP_KEY + " VALUES ("
            + ":customerId, :metricName, :metricType, :time, :value, :bizCode, "
            + ":resourceJson, :attributesJson, :dpHash)";

    private final NamedParameterJdbcTemplate namedJdbc;
    private final OtlpJsonParser parser;

    public JdbcMetricsRepository(NamedParameterJdbcTemplate namedJdbc, OtlpJsonParser parser) {
        this.namedJdbc = namedJdbc;
        this.parser = parser;
    }

    @Override
    public void write(List<String> payloads) {
        // 解析失败按记录粒度隔离（T08 评审 F-1，口径同 JdbcSpansRepository）
        List<MetricRow> rows = new ArrayList<>(payloads.size());
        int dropped = 0;
        for (int i = 0; i < payloads.size(); i++) {
            try {
                rows.addAll(parser.parseMetrics(payloads.get(i)));
            } catch (RuntimeException e) {
                dropped++;
                LOG.error("metrics 载荷[{}] 解析失败，跳过该条（不重试，防毒丸卡死分区）", i, e);
            }
        }
        if (dropped > 0) {
            LOG.warn("metrics 批解析隔离 {} 条坏载荷 / 共 {} 条", dropped, payloads.size());
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

    private static MapSqlParameterSource toParamSource(MetricRow row) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("customerId", row.customerId());
        params.addValue("metricName", row.metricName());
        params.addValue("metricType", row.metricType());
        params.addValue("time", Timestamp.from(row.time()));
        params.addValue("value", row.value());
        params.addValue("bizCode", row.bizCode());
        params.addValue("resourceJson", row.resourceJson());
        params.addValue("attributesJson", row.attributesJson());
        params.addValue("dpHash", row.dpHash());
        return params;
    }
}

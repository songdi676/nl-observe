package com.nlolobserve.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.ddl.DdlScripts;
import com.nlolobserve.storage.config.DatabendDataSourceConfig;
import com.nlolobserve.storage.config.DatabendProperties;
import com.nlolobserve.storage.parse.OtlpFixtures;
import com.nlolobserve.storage.parse.OtlpJsonParser;
import com.nlolobserve.storage.repo.JdbcLogsRepository;
import com.nlolobserve.storage.repo.JdbcMetricsRepository;
import com.nlolobserve.storage.repo.JdbcSpansRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.databend.DatabendContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T08 验收（集成，Testcontainers Databend，env-gated：Docker/镜像不可得时按 assumption 跳过，
 * 消费链路降级覆盖由 T07 Kafka IT + 内存桩承担）：
 * - 灌样例 → 三表行数/字段断言（customer_id/biz_code/effective_duration_ms/service_name、
 *   dp_hash/row_hash 落列、attributes_json 可经 Databend 方言 json_extract_path_text 取值）
 * - 同 span 灌两次 → FINAL 查询 1 行（去重身份=聚簇键，写入侧 REPLACE INTO，详设 §3/§4.3）
 *
 * 镜像可用时本测试是 REPLACE INTO/FINAL 方言正确性的最终验收（同 T09 评审 Major-2 口径）。
 */
class StorageDatabendIntegrationTest {

    private static final String DEFAULT_IMAGE_NAME = "datafuselabs/databend:latest";

    private static DatabendContainer databend;
    private static JdbcTemplate jdbc;
    private static JdbcSpansRepository spansRepository;
    private static JdbcMetricsRepository metricsRepository;
    private static JdbcLogsRepository logsRepository;

    @BeforeAll
    static void startDatabendAndCreateSchema() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用：跳过 Databend 集成测试");
        String imageName = envOr("NL_OBSERVE_DATABEND_IMAGE", DEFAULT_IMAGE_NAME);
        try {
            databend = new DatabendContainer(DockerImageName.parse(imageName));
            databend.start();
        } catch (Throwable t) {
            if (databend != null) {
                databend.stop();
                databend = null;
            }
            assumeTrue(false, "Databend 镜像 " + imageName + " 不可得：跳过（人工环境验证），原因: " + t);
        }

        // 建表：DdlScripts.initScript() 是唯一 DDL 事实来源（scripts/databend/001-init.sql 同源）
        DatabendProperties props = new DatabendProperties();
        props.setUrl(withPresignedDisabled(databend.getJdbcUrl()));
        props.setUsername(databend.getUsername());
        props.setPassword(databend.getPassword());
        // 生产配置路径组装 Hikari 写池，顺带覆盖 presigned_url_disabled 自检通过路径
        props.validate();
        DataSource dataSource = new DatabendDataSourceConfig().observeStorageDataSource(props);
        jdbc = new JdbcTemplate(dataSource);
        for (String raw : DdlScripts.initScript().split(";")) {
            String single = stripLeadingComments(raw).trim();
            if (!single.isEmpty()) {
                try (Statement stmt = dataSource.getConnection().createStatement()) {
                    stmt.execute(single);
                }
            }
        }
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        OtlpJsonParser parser = new OtlpJsonParser(new ObjectMapper());
        spansRepository = new JdbcSpansRepository(namedJdbc, parser);
        metricsRepository = new JdbcMetricsRepository(namedJdbc, parser);
        logsRepository = new JdbcLogsRepository(namedJdbc, parser);
    }

    @BeforeEach
    void truncateTables() {
        if (databend != null) {
            jdbc.execute("TRUNCATE TABLE otel_spans");
            jdbc.execute("TRUNCATE TABLE otel_metrics");
            jdbc.execute("TRUNCATE TABLE otel_logs");
        }
    }

    @AfterAll
    static void stopDatabend() {
        if (databend != null) {
            databend.stop();
        }
    }

    @Test
    void feedSamples_threeTablesRowCountsAndFields() {
        spansRepository.write(List.of(OtlpFixtures.sampleSpans()));
        metricsRepository.write(List.of(OtlpFixtures.sampleMetrics()));
        logsRepository.write(List.of(OtlpFixtures.sampleLogs()));

        // otel_spans：1 行，身份列与 Databend 方言可查的 attributes_json
        Map<String, Object> span = jdbc.queryForMap("SELECT * FROM otel_spans");
        assertThat(span.get("customer_id")).isEqualTo("c-1");
        assertThat(span.get("trace_id")).isEqualTo(OtlpFixtures.TRACE_ID);
        assertThat(span.get("span_id")).isEqualTo(OtlpFixtures.SPAN_ID);
        assertThat(span.get("biz_code")).isEqualTo("ORDER");
        assertThat(span.get("service_name")).isEqualTo("shop-web");
        assertThat(((Number) span.get("effective_duration_ms")).doubleValue()).isEqualTo(250.0);
        // Databend JDBC 驱动对 SELECT * 宽表以 String 返回 TIMESTAMP，改用 SQL 端 epoch 断言写入值正确
        Long startEpoch = jdbc.queryForObject("SELECT to_unix_timestamp(start_time) FROM otel_spans", Long.class);
        assertThat(startEpoch).isEqualTo(1_760_512_800L);
        String bizStatus = jdbc.queryForObject(
                "SELECT json_extract_path_text(attributes_json, '$.\"biz.status\"') FROM otel_spans",
                String.class);
        assertThat(bizStatus).isEqualTo("success");

        // otel_metrics：2 行（两个 data point），dp_hash 非空且互异
        List<Map<String, Object>> metrics = jdbc.queryForList("SELECT * FROM otel_metrics");
        assertThat(metrics).hasSize(2);
        assertThat(metrics).allSatisfy(m -> assertThat(m.get("dp_hash")).asString().isNotBlank());
        Object hashA = metrics.get(0).get("dp_hash");
        Object hashB = metrics.get(1).get("dp_hash");
        assertThat(hashA).isNotEqualTo(hashB);
        List<Double> values = jdbc.queryForList(
                "SELECT value FROM otel_metrics ORDER BY metric_name", Double.class);
        // 字典序：nl.business_duration(250.0) < nl.first_screen_load(1000.0)
        assertThat(values).containsExactly(250.0, 1000.0);

        // otel_logs：1 行，row_hash 非空
        List<Map<String, Object>> logs = jdbc.queryForList("SELECT * FROM otel_logs");
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("row_hash")).asString().isNotBlank();
        assertThat(logs.get(0).get("body")).isEqualTo("user logged in");
    }

    @Test
    void spanEvents_roundTripThroughEventsJsonColumn() throws Exception {
        // T10 契约端到端（T08 评审 Major 闭环）：OTLP events → events_json 落列 → 查询端可解析
        spansRepository.write(List.of(OtlpFixtures.spanEnvelopeWithEvents(OtlpFixtures.TRACE_ID,
                OtlpFixtures.SPAN_ID, "biz.business.ORDER", OtlpFixtures.bizRootAttributes(),
                OtlpFixtures.bizEvents())));

        String eventsJson = jdbc.queryForObject("SELECT events_json FROM otel_spans", String.class);
        List<Map<String, Object>> events =
                new ObjectMapper().readValue(eventsJson, new TypeReference<>() {
                });
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("name", "biz.suspend_start")
                .containsEntry("time", "2025-10-15T07:20:01Z");
        assertThat(events.get(1)).containsEntry("name", "biz.resume");

        // 无事件 span 以同身份键 REPLACE 覆盖 → events_json 回到 '[]'（列缺省同值）
        spansRepository.write(List.of(OtlpFixtures.sampleSpans()));
        String replaced = jdbc.queryForObject(
                "SELECT events_json FROM otel_spans FINAL WHERE trace_id = ?", String.class,
                OtlpFixtures.TRACE_ID);
        assertThat(replaced).isEqualTo("[]");
    }

    @Test
    void sameSpanTwice_finalQueryReturnsOneRow() {
        spansRepository.write(List.of(OtlpFixtures.sampleSpans()));
        spansRepository.write(List.of(OtlpFixtures.sampleSpans()));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM otel_spans FINAL WHERE trace_id = ? AND customer_id = ?",
                Integer.class, OtlpFixtures.TRACE_ID, "c-1");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameSpanTwice_secondWriteReplacesRowContent() {
        spansRepository.write(List.of(OtlpFixtures.sampleSpans()));
        // 同身份键（customer_id/trace_id/span_id/start_time/end_time）重发，attributes 变化
        String updated = OtlpFixtures.spanEnvelope(OtlpFixtures.TRACE_ID, OtlpFixtures.SPAN_ID,
                "biz.business.ORDER",
                "[{\"key\":\"biz.code\",\"value\":{\"stringValue\":\"ORDER\"}},"
                        + "{\"key\":\"biz.status\",\"value\":{\"stringValue\":\"fail\"}}]");
        spansRepository.write(List.of(updated));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM otel_spans FINAL WHERE trace_id = ?",
                Integer.class, OtlpFixtures.TRACE_ID);
        assertThat(count).isEqualTo(1);
        String status = jdbc.queryForObject(
                "SELECT json_extract_path_text(attributes_json, '$.\"biz.status\"') FROM otel_spans FINAL",
                String.class);
        assertThat(status).isEqualTo("fail");
    }

    @Test
    void sameMetricDataPointTwice_finalCountsUnchanged() {
        metricsRepository.write(List.of(OtlpFixtures.sampleMetrics()));
        metricsRepository.write(List.of(OtlpFixtures.sampleMetrics()));

        Integer count = jdbc.queryForObject("SELECT count(*) FROM otel_metrics FINAL", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void sameLogRecordTwice_finalQueryReturnsOneRow() {
        logsRepository.write(List.of(OtlpFixtures.sampleLogs()));
        logsRepository.write(List.of(OtlpFixtures.sampleLogs()));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM otel_logs FINAL WHERE customer_id = ?",
                Integer.class, "c-1");
        assertThat(count).isEqualTo(1);
    }

    private static String withPresignedDisabled(String jdbcUrl) {
        String url = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "presigned_url_disabled=true";
        return url;
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** 去掉语句前置的 -- 行注释（与 backend-observe-common 的 DatabendInitSqlIntegrationTest 同口径）。 */
    private static String stripLeadingComments(String statement) {
        String[] lines = statement.split("\n");
        int first = 0;
        while (first < lines.length && lines[first].trim().startsWith("--")) {
            first++;
        }
        return String.join("\n", Arrays.copyOfRange(lines, first, lines.length));
    }
}

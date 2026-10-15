package com.nlolobserve.query.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.ddl.DdlScripts;
import com.nlolobserve.common.dto.BizMetric;
import com.nlolobserve.common.dto.BusinessItem;
import com.nlolobserve.common.dto.SpanDetail;
import com.nlolobserve.common.dto.SpanNode;
import com.nlolobserve.query.config.DatabendDataSourceConfig;
import com.nlolobserve.query.config.DatabendProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.databend.DatabendContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T09 方言集成测试（评审 Major-2/M2 落实）：QueryRepository 五段 SQL 模板对真实 Databend
 * 实跑。mock JDBC 单测发现不了方言错误——本模块曾因此出 Critical（json_extract_string
 * 是 ClickHouse 函数，Databend 无此函数，IT 实跑复现 QueryError 1008 后改为
 * json_extract_path_text）。
 *
 * 数据源经生产路径 {@link DatabendDataSourceConfig} 组装（评审 m4：覆盖 Hikari
 * readOnly 池在真实驱动下的行为）。
 *
 * env-gated（详设 §6.2）：Docker 不可用或镜像不可得时按 assumption 跳过，不判失败；
 * 镜像可用时本测试是 DDL/模板方言正确性的最终验收。
 */
class QueryRepositoryDatabendIntegrationTest {

    private static final String DEFAULT_IMAGE_NAME = "datafuselabs/databend:latest";

    private static DatabendContainer databend;
    private static NamedParameterJdbcTemplate namedJdbc;
    private static QueryRepository repository;

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    /** s-root 的 attributes_json（biz.status/end_reason 为 json_extract_path_text 取值对象） */
    private static final String ROOT_ATTRS_SUCCESS =
            "{\"nl.customer_id\":\"c-1\",\"biz.code\":\"ORDER\",\"biz.status\":\"success\","
                    + "\"biz.end_reason\":\"explicit\",\"biz.total_suspended_ms\":100,"
                    + "\"biz.effective_duration_ms\":250}";
    private static final String ROOT_ATTRS_FAIL =
            "{\"nl.customer_id\":\"c-1\",\"biz.code\":\"ORDER\",\"biz.status\":\"fail\","
                    + "\"biz.end_reason\":\"unmount\",\"biz.total_suspended_ms\":0,"
                    + "\"biz.effective_duration_ms\":100}";

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

        // 建表：T02/T03 的 DdlScripts.initScript() 是唯一 DDL 事实来源（scripts/databend/001-init.sql 同源）
        DatabendProperties props = new DatabendProperties();
        props.setUrl(withPresignedDisabled(databend.getJdbcUrl()));
        props.setUsername(databend.getUsername());
        props.setPassword(databend.getPassword());
        // 生产配置路径组装 Hikari 只读池（m4），顺带覆盖 presigned_url_disabled 自检通过路径
        props.validate();
        DataSource dataSource = new DatabendDataSourceConfig().observeQueryDataSource(props);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String raw : DdlScripts.initScript().split(";")) {
            String single = stripLeadingComments(raw).trim();
            if (!single.isEmpty()) {
                try (Statement stmt = dataSource.getConnection().createStatement()) {
                    stmt.execute(single);
                }
            }
        }
        namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        repository = new QueryRepository(namedJdbc, new ObjectMapper());
        seedSamples(jdbc);
    }

    @AfterAll
    static void stopDatabend() {
        if (databend != null) {
            databend.stop();
        }
    }

    /** 样例：c-1 一个 trace 内 2 个 span（业务 root ORDER success + 子 span）+ 同码第 2 业务 fail；
     *  c-1 两条首屏指标（1000.0/1830.5）；c-2 无任何数据（跨客户隔离断言用）。 */
    private static void seedSamples(JdbcTemplate jdbc) {
        Timestamp rootStart = Timestamp.from(Instant.parse("2026-09-15T10:00:00Z"));
        Timestamp rootEnd = Timestamp.from(Instant.parse("2026-09-15T10:00:03Z"));
        jdbc.update("INSERT INTO otel_spans (customer_id, trace_id, span_id, parent_span_id, name, kind,"
                        + " start_time, end_time, status_code, biz_code, service_name, effective_duration_ms,"
                        + " resource_json, scope_json, attributes_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "c-1", "t-1", "s-root", "", "biz.business.ORDER", 1,
                rootStart, rootEnd, 1, "ORDER", "shop-web", 250.0,
                "{\"service.version\":\"1.0\"}", "{\"name\":\"nl-observe\"}", ROOT_ATTRS_SUCCESS);
        jdbc.update("INSERT INTO otel_spans (customer_id, trace_id, span_id, parent_span_id, name, kind,"
                        + " start_time, end_time, status_code, biz_code, service_name, effective_duration_ms,"
                        + " resource_json, scope_json, attributes_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "c-1", "t-1", "s-child", "s-root", "GET /checkout", 3,
                Timestamp.from(Instant.parse("2026-09-15T10:00:01Z")),
                Timestamp.from(Instant.parse("2026-09-15T10:00:01.040Z")), 1, "", "shop-web", 40.0,
                "{\"service.version\":\"1.0\"}", "{\"name\":\"nl-observe\"}",
                "{\"http.request.method\":\"GET\"}");
        // 同 bizCode 第二个业务 root（fail）：验证 perBiz 聚合与业务列表多行
        jdbc.update("INSERT INTO otel_spans (customer_id, trace_id, span_id, parent_span_id, name, kind,"
                        + " start_time, end_time, status_code, biz_code, service_name, effective_duration_ms,"
                        + " resource_json, scope_json, attributes_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "c-1", "t-2", "s-root2", "", "biz.business.ORDER", 1,
                Timestamp.from(Instant.parse("2026-09-16T10:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-16T10:00:01Z")), 2, "ORDER", "shop-web", 100.0,
                "{\"service.version\":\"1.0\"}", "{\"name\":\"nl-observe\"}", ROOT_ATTRS_FAIL);

        jdbc.update("INSERT INTO otel_metrics (customer_id, metric_name, metric_type, time, value,"
                        + " resource_json, attributes_json, dp_hash) VALUES (?,?,?,?,?,?,?,?)",
                "c-1", "nl.first_screen_load", 4,
                Timestamp.from(Instant.parse("2026-09-15T10:00:02Z")), 1000.0,
                "{}", "{}", "dp-1");
        jdbc.update("INSERT INTO otel_metrics (customer_id, metric_name, metric_type, time, value,"
                        + " resource_json, attributes_json, dp_hash) VALUES (?,?,?,?,?,?,?,?)",
                "c-1", "nl.first_screen_load", 4,
                Timestamp.from(Instant.parse("2026-09-16T10:00:02Z")), 1830.5,
                "{}", "{}", "dp-2");
    }

    @Test
    void businessItems_templatesRunOnRealDatabend_andExtractStatusFromAttributes() {
        List<BusinessItem> items = repository.findBusinessItems("c-1", FROM, TO, null);
        assertThat(items).hasSize(2);
        // 按 start_time 倒序：s-root2（fail）在前
        assertThat(items.get(0).getStatus()).isEqualTo("fail");
        assertThat(items.get(0).getEndReason()).isEqualTo("unmount");
        assertThat(items.get(1).getStatus()).isEqualTo("success");
        assertThat(items.get(1).getEndReason()).isEqualTo("explicit");
        assertThat(items).extracting(BusinessItem::getBizCode).containsOnly("ORDER");
        assertThat(items.get(1).getEffectiveDurationMs()).isEqualTo(250.0);

        // 哨兵过滤：指定 bizCode 命中，不存在的 bizCode 空结果
        assertThat(repository.findBusinessItems("c-1", FROM, TO, "ORDER")).hasSize(2);
        assertThat(repository.findBusinessItems("c-1", FROM, TO, "PAY")).isEmpty();
    }

    @Test
    void spansByTrace_returnsTreeWithParsedAttributes() {
        List<SpanNode> spans = repository.findSpans("c-1", "t-1");
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).getSpanId()).isEqualTo("s-root");
        assertThat(spans.get(1).getSpanId()).isEqualTo("s-child");
        assertThat(spans.get(1).getParentSpanId()).isEqualTo("s-root");
        assertThat(spans.get(1).getAttributes()).containsEntry("http.request.method", "GET");
        assertThat(spans.get(0).getAttributes()).containsEntry("biz.status", "success");
    }

    @Test
    void spanDetail_parsesResourceScopeAttributesToObject() {
        SpanDetail detail = repository.findSpan("c-1", "t-1", "s-root").orElseThrow();
        assertThat(detail.getServiceName()).isEqualTo("shop-web");
        assertThat(detail.getResource()).containsEntry("service.version", "1.0");
        assertThat(detail.getScope()).containsEntry("name", "nl-observe");
        assertThat(detail.getAttributes()).containsEntry("biz.status", "success");
        assertThat(detail.getEffectiveDurationMs()).isEqualTo(250.0);
        assertThat(repository.findSpan("c-1", "t-1", "no-such-span")).isEmpty();
    }

    @Test
    void firstScreenLoadP95_queryRunsOnRealDatabend() {
        // 不断言精确插值：Databend quantile 语义与 ClickHouse 线性插值不同（T11 对拍前
        // 以真实 Databend 固化口径，见 T09 评审 Minor）；此处验证方言可执行且值域正确
        Double p95 = repository.findFirstScreenLoadP95(FROM, TO, "c-1");
        assertThat(p95).isNotNull();
        assertThat(p95).isBetween(1000.0, 1830.5);
        // 运维视图（customerId 空串）同样可查
        assertThat(repository.findFirstScreenLoadP95(FROM, TO, "")).isNotNull();
    }

    @Test
    void perBizSummary_aggregatesCountAndSuccessRateOnRealDatabend() {
        List<BizMetric> perBiz = repository.findPerBizMetrics(FROM, TO, "c-1", null);
        assertThat(perBiz).hasSize(1);
        BizMetric order = perBiz.get(0);
        assertThat(order.getBizCode()).isEqualTo("ORDER");
        assertThat(order.getCount()).isEqualTo(2); // 两个 ORDER 业务 root（success + fail）
        assertThat(order.getSuccessRate()).isEqualTo(0.5);
    }

    @Test
    void crossCustomer_isolationAndOpsView() {
        // c-2 无数据 → 客户维度空结果
        assertThat(repository.findBusinessItems("c-2", FROM, TO, null)).isEmpty();
        assertThat(repository.findSpans("c-2", "t-1")).isEmpty();
        // 运维视图（空串）可看到 c-1 数据
        assertThat(repository.findBusinessItems("c-1", FROM, TO, null)).isNotEmpty();
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

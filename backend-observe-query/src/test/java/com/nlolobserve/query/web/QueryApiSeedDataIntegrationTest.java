package com.nlolobserve.query.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import com.nlolobserve.common.ddl.DdlScripts;
import com.nlolobserve.query.config.DatabendDataSourceConfig;
import com.nlolobserve.query.config.DatabendProperties;
import com.nlolobserve.query.config.QueryProperties;
import com.nlolobserve.query.service.OpsViewRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.databend.DatabendContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T10/T11 验收（test_cases #1）：预置数据 → businesses / trace / span / metrics-summary
 * 四端点数据断言（T11 为 quantile P95 + 成功率与手工计算对拍、运维视图限流 42901）；
 * 参数错误/无数据 → 错误码断言。
 *
 * <p>与 {@code QueryApiTest}（T09，mock repo 契约层）互补：本类经 Testcontainers 真实 Databend
 * （生产 {@link DatabendDataSourceConfig} 只读池 + 启动 SELECT 1 自检）+ MockMvc 全栈
 * （Controller→Service→QueryRepository→Databend），断言预置数据的真实返回形态（详设 §2.1-§2.4）。
 *
 * <p>用例顺序显式 {@link Order}：运维视图限流用例（共享限流桶）须在所有运维视图
 * （不带 customerId）用例之后执行，排空前 60 次/分配额，避免污染其前序运维视图
 * 用例的 200 断言（Order(15)/(16) 走参数校验/客户作用域，不触碰运维视图配额，可在其后）。
 *
 * <p>env-gated（详设 §6.2）：Docker 不可用或镜像不可得时按 assumption 跳过，不判失败。
 */
@SpringBootTest(properties = {
        "observe.ops-view-rate-limit-per-min=60",
        "logging.audit.file=target/audit/audit-api-seed-it.log"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryApiSeedDataIntegrationTest {

    private static final String DEFAULT_IMAGE_NAME = "datafuselabs/databend:latest";
    /** 固定时钟取值（评审 M-1：窗口永不推进，超频验收断言确定化） */
    private static final long FROZEN_NOW_MS = 1_787_000_000_000L;

    /** 预置样例（与断言一一对应）：c-1/t-1 = 业务 root（success）+ REST 子 span（白名单 4 属性）；
     *  c-1/t-2 = 同 bizCode 第二业务（fail/unmount）；c-2 无任何数据（无数据 40401 断言用）。 */
    private static final String ROOT_ATTRS_SUCCESS =
            "{\"nl.customer_id\":\"c-1\",\"biz.code\":\"ORDER\",\"biz.status\":\"success\","
                    + "\"biz.end_reason\":\"explicit\",\"biz.total_suspended_ms\":100,"
                    + "\"biz.effective_duration_ms\":250}";
    private static final String ROOT_ATTRS_FAIL =
            "{\"nl.customer_id\":\"c-1\",\"biz.code\":\"ORDER\",\"biz.status\":\"fail\","
                    + "\"biz.end_reason\":\"unmount\",\"biz.total_suspended_ms\":0,"
                    + "\"biz.effective_duration_ms\":100}";
    /** REST span 属性白名单（AC-04 四键，详设 §1.1） */
    private static final String REST_ATTRS_WHITELIST =
            "{\"http.request.method\":\"GET\",\"http.route\":\"/checkout\","
                    + "\"http.response.status_code\":200,\"nl.duration_ms\":40}";
    /** 业务 root span 的挂起/恢复事件（events_json 契约见 DdlScripts.OTEL_SPANS 注释；
     *  T10 评审 Critical：AC-2 要求 span 树含 events，预置非空事件并断言透出） */
    private static final String ROOT_EVENTS =
            "[{\"name\":\"biz.suspend_start\",\"time\":\"2026-09-15T10:00:01Z\",\"attributes\":{\"biz.biz_id\":\"s-root\"}},"
                    + "{\"name\":\"biz.resume\",\"time\":\"2026-09-15T10:00:02Z\",\"attributes\":{}}]";

    private static DatabendContainer databend;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void databendProps(DynamicPropertyRegistry registry) {
        if (databend == null) {
            // @BeforeAll 的 assumption 已跳过全部用例，上下文不会走到创建；兜底保证属性可解析
            registry.add("observe.databend.url",
                    () -> "jdbc:databend://localhost:8000/observe?presigned_url_disabled=true");
            registry.add("observe.databend.username", () -> "n/a");
            registry.add("observe.databend.password", () -> "n/a");
            registry.add("observe.databend.check-on-startup", () -> "false");
            return;
        }
        registry.add("observe.databend.url", () -> withPresignedDisabled(databend.getJdbcUrl()));
        registry.add("observe.databend.username", () -> databend.getUsername());
        registry.add("observe.databend.password", () -> databend.getPassword());
    }

    @BeforeAll
    static void startDatabendCreateSchemaAndSeed() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker 不可用：跳过真实 Databend 预置数据集成测试");
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

        // 建表：DdlScripts.initScript() 唯一 DDL 事实来源；数据源经生产路径组装（同 T09 IT 口径）
        DatabendProperties props = new DatabendProperties();
        props.setUrl(withPresignedDisabled(databend.getJdbcUrl()));
        props.setUsername(databend.getUsername());
        props.setPassword(databend.getPassword());
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
        seedSamples(jdbc);
        seedMetricsSamples(jdbc);
    }

    @AfterAll
    static void stopDatabend() {
        if (databend != null) {
            databend.stop();
            databend = null;
        }
    }

    private static void seedSamples(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO otel_spans (customer_id, trace_id, span_id, parent_span_id, name, kind,"
                        + " start_time, end_time, status_code, biz_code, service_name, effective_duration_ms,"
                        + " resource_json, scope_json, attributes_json, events_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "c-1", "t-1", "s-root", "", "biz.business.ORDER", 1,
                Timestamp.from(Instant.parse("2026-09-15T10:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-15T10:00:03Z")), 1, "ORDER", "shop-web", 250.0,
                "{\"service.version\":\"1.0\"}", "{\"name\":\"nl-observe\"}", ROOT_ATTRS_SUCCESS, ROOT_EVENTS);
        jdbc.update("INSERT INTO otel_spans (customer_id, trace_id, span_id, parent_span_id, name, kind,"
                        + " start_time, end_time, status_code, biz_code, service_name, effective_duration_ms,"
                        + " resource_json, scope_json, attributes_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "c-1", "t-1", "s-child", "s-root", "GET /checkout", 2,
                Timestamp.from(Instant.parse("2026-09-15T10:00:01Z")),
                Timestamp.from(Instant.parse("2026-09-15T10:00:01.040Z")), 1, "", "shop-web", 40.0,
                "{\"service.version\":\"1.0\"}", "{\"name\":\"nl-observe\"}", REST_ATTRS_WHITELIST);
        jdbc.update("INSERT INTO otel_spans (customer_id, trace_id, span_id, parent_span_id, name, kind,"
                        + " start_time, end_time, status_code, biz_code, service_name, effective_duration_ms,"
                        + " resource_json, scope_json, attributes_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "c-1", "t-2", "s-root2", "", "biz.business.ORDER", 1,
                Timestamp.from(Instant.parse("2026-09-16T10:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-16T10:00:01Z")), 2, "ORDER", "shop-web", 100.0,
                "{\"service.version\":\"1.0\"}", "{\"name\":\"nl-observe\"}", ROOT_ATTRS_FAIL);
    }

    // ---------- T11 metrics/summary 预置（详设 §2.4 对拍数据集） ----------
    // 窗口 2026-08-01..2026-08-31：与 T10 断言（09 窗口、t-1/t-2/s-root*）完全隔离，
    // 新增行不影响 businesses/trace/span 既有用例的数据断言。
    //
    // quantile 对拍口径（T09 评审 Minor 落实：以真实 Databend 固化后才可断精确值）。
    // T11 实跑固化（datafuselabs/databend:latest，2026-10）：Databend quantile(level)(x) 为
    // 精确「下秩」口径 v[floor(level*(n-1))]（0 基、无插值）——numbers(20)→18、numbers(5)→3、
    // numbers(10)→8.0、空集→NULL，与 ClickHouse quantile 的 BTD/线性插值语义不同。
    // 数据集仍按「多种口径重合」设计，断言对该实现细节不敏感：
    // 取 n=21 且 0.95*(n-1)=19.0 恰落在第 20 小数据点上（下秩/线性/最近秩三法同值）；
    // 运维视图（跨客户）23 点取「最大两个值相等」，下秩 v[20]=1830 与线性插值端点同值。

    /** metrics 预置窗口（与 T10 的 09 窗口隔离） */
    private static final String METRICS_FROM = "2026-08-01";
    private static final String METRICS_TO = "2026-08-31";

    /** c-1 首屏指标 21 点：1810..1830 → P95=1829.0（第 20 小） */
    private static final int FIRST_SCREEN_POINTS = 21;
    private static final double FIRST_SCREEN_BASE = 1810.0;
    private static final double FIRST_SCREEN_P95_MANUAL = 1829.0;

    /** c-2 首屏指标 2 点：均 1830.0（=c-1 最大值）→ 运维视图 23 点 P95=1830.0；
     *  若运维视图误按单客户过滤则得 1829.0 → 跨客户语义可判别 */
    private static final double OPS_EXTRA_VALUE = 1830.0;
    private static final double OPS_VIEW_P95_MANUAL = 1830.0;

    /** c-1 ORDER 业务 root 21 个：effective_duration_ms 100..300（步长 10）→ P95=290.0；
     *  20 success + 1 fail → successRate=20/21 */
    private static final int ORDER_SPANS = 21;
    private static final double ORDER_P95_MANUAL = 290.0;
    private static final int ORDER_SUCCESS_COUNT = 20;

    /** c-1 PAY 业务 root 5 个：effective_duration_ms 全 1234.0（全等值 → 任意 quantile 口径 P95=1234.0）；
     *  4 success + 1 fail → successRate=0.8 */
    private static final int PAY_SPANS = 5;
    private static final double PAY_DURATION = 1234.0;
    private static final int PAY_SUCCESS_COUNT = 4;

    private static void seedMetricsSamples(JdbcTemplate jdbc) {
        Instant metricBase = Instant.parse("2026-08-15T10:00:00Z");
        for (int i = 0; i < FIRST_SCREEN_POINTS; i++) {
            insertFirstScreenMetric(jdbc, "c-1", metricBase.plusSeconds(i * 60L), FIRST_SCREEN_BASE + i, "a" + i);
        }
        for (int i = 0; i < 2; i++) {
            insertFirstScreenMetric(jdbc, "c-2", metricBase.plusSeconds((100 + i) * 60L), OPS_EXTRA_VALUE, "b" + i);
        }
        Instant spanBase = Instant.parse("2026-08-10T10:00:00Z");
        for (int i = 0; i < ORDER_SPANS; i++) {
            insertBizRootSpan(jdbc, "c-1", "t-m-order-" + i, "s-m-order-" + i, "ORDER",
                    spanBase.plusSeconds(i * 60L), 100.0 + i * 10, i < ORDER_SUCCESS_COUNT);
        }
        for (int i = 0; i < PAY_SPANS; i++) {
            insertBizRootSpan(jdbc, "c-1", "t-m-pay-" + i, "s-m-pay-" + i, "PAY",
                    spanBase.plusSeconds((200 + i) * 60L), PAY_DURATION, i < PAY_SUCCESS_COUNT);
        }
    }

    private static void insertFirstScreenMetric(JdbcTemplate jdbc, String customerId, Instant time,
            double value, String dpSuffix) {
        jdbc.update("INSERT INTO otel_metrics (customer_id, metric_name, metric_type, time, value,"
                        + " resource_json, attributes_json, dp_hash) VALUES (?,?,?,?,?,?,?,?)",
                customerId, "nl.first_screen_load", 4, Timestamp.from(time), value,
                "{}", "{}", "dp-m-" + dpSuffix);
    }

    private static void insertBizRootSpan(JdbcTemplate jdbc, String customerId, String traceId, String spanId,
            String bizCode, Instant start, double effectiveDurationMs, boolean success) {
        jdbc.update("INSERT INTO otel_spans (customer_id, trace_id, span_id, parent_span_id, name, kind,"
                        + " start_time, end_time, status_code, biz_code, service_name, effective_duration_ms,"
                        + " resource_json, scope_json, attributes_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                customerId, traceId, spanId, "", "biz.business." + bizCode, 1,
                Timestamp.from(start), Timestamp.from(start.plusSeconds(2)), success ? 1 : 2,
                bizCode, "shop-web", effectiveDurationMs,
                "{}", "{}",
                "{\"nl.customer_id\":\"" + customerId + "\",\"biz.code\":\"" + bizCode
                        + "\",\"biz.status\":\"" + (success ? "success" : "fail")
                        + "\",\"biz.end_reason\":\"" + (success ? "explicit" : "unmount") + "\"}");
    }

    // ---------- businesses（详设 §2.1）：预置数据 → 数据断言 ----------

    @Order(1)
    @Test
    void businesses_returnsSeededItemsWithAllFields() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.length()").value(2))
                // 按 start_time 倒序：t-2 s-root2（fail/unmount）在前
                .andExpect(jsonPath("$.data[0].bizId").value("s-root2"))
                .andExpect(jsonPath("$.data[0].bizCode").value("ORDER"))
                .andExpect(jsonPath("$.data[0].traceId").value("t-2"))
                .andExpect(jsonPath("$.data[0].startTime").value("2026-09-16T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].effectiveDurationMs").value(100.0))
                .andExpect(jsonPath("$.data[0].status").value("fail"))
                .andExpect(jsonPath("$.data[0].endReason").value("unmount"))
                .andExpect(jsonPath("$.data[1].bizId").value("s-root"))
                .andExpect(jsonPath("$.data[1].traceId").value("t-1"))
                .andExpect(jsonPath("$.data[1].effectiveDurationMs").value(250.0))
                .andExpect(jsonPath("$.data[1].status").value("success"))
                .andExpect(jsonPath("$.data[1].endReason").value("explicit"));
    }

    @Order(2)
    @Test
    void businesses_bizCodeFilter_hitsSentinel() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-01").param("to", "2026-09-30").param("bizCode", "ORDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2));
        // 窗口收窄到单日：仅 t-2 业务命中
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-16").param("to", "2026-09-16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].bizId").value("s-root2"));
    }

    // ---------- businesses：参数错误 → 40001；无数据 → 40401 ----------

    @Order(3)
    @Test
    void businesses_missingFromOrTo_returns40001() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses").param("to", "2026-09-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        mockMvc.perform(get("/api/observe/customers/c-1/businesses").param("from", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Order(4)
    @Test
    void businesses_spanOver31Days_returns40001() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-01").param("to", "2026-10-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Order(5)
    @Test
    void businesses_malformedDate_returns40001() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "20260901").param("to", "2026-09-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Order(6)
    @Test
    void businesses_noData_returns40401() throws Exception {
        // 未知客户（未预置数据）
        mockMvc.perform(get("/api/observe/customers/c-2/businesses")
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
        // 已知客户、窗口外无数据
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---------- trace span 树（详设 §2.2）：预置数据 → 数据断言 ----------

    @Order(7)
    @Test
    void trace_returnsSeededSpanTreeWithRestWhitelistAttributes() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/traces/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.traceId").value("t-1"))
                .andExpect(jsonPath("$.data.spans.length()").value(2))
                // 按 start_time 正序：s-root → s-child
                .andExpect(jsonPath("$.data.spans[0].spanId").value("s-root"))
                .andExpect(jsonPath("$.data.spans[0].parentSpanId").value(""))
                .andExpect(jsonPath("$.data.spans[0].name").value("biz.business.ORDER"))
                .andExpect(jsonPath("$.data.spans[0].attributes['biz.status']").value("success"))
                // events 全链路透出（T10 评审 Critical：DDL events_json 列 → SQL SELECT → EventNode 映射），
                // 预置非空事件并逐字段断言，杜绝「恒空数组」掩盖链路断裂
                .andExpect(jsonPath("$.data.spans[0].events.length()").value(2))
                .andExpect(jsonPath("$.data.spans[0].events[0].name").value("biz.suspend_start"))
                .andExpect(jsonPath("$.data.spans[0].events[0].time").value("2026-09-15T10:00:01Z"))
                .andExpect(jsonPath("$.data.spans[0].events[0].attributes['biz.biz_id']").value("s-root"))
                .andExpect(jsonPath("$.data.spans[0].events[1].name").value("biz.resume"))
                .andExpect(jsonPath("$.data.spans[0].events[1].attributes").isEmpty())
                .andExpect(jsonPath("$.data.spans[1].spanId").value("s-child"))
                .andExpect(jsonPath("$.data.spans[1].parentSpanId").value("s-root"))
                .andExpect(jsonPath("$.data.spans[1].kind").value(2))
                .andExpect(jsonPath("$.data.spans[1].durationMs").value(40.0))
                .andExpect(jsonPath("$.data.spans[1].effectiveDurationMs").value(40.0))
                // REST span 白名单 4 属性（AC-04）原样透出
                .andExpect(jsonPath("$.data.spans[1].attributes['http.request.method']").value("GET"))
                .andExpect(jsonPath("$.data.spans[1].attributes['http.route']").value("/checkout"))
                .andExpect(jsonPath("$.data.spans[1].attributes['http.response.status_code']").value(200))
                .andExpect(jsonPath("$.data.spans[1].attributes['nl.duration_ms']").value(40))
                // 无事件 span：列缺省 DEFAULT '[]' → 空数组
                .andExpect(jsonPath("$.data.spans[1].events").isArray())
                .andExpect(jsonPath("$.data.spans[1].events.length()").value(0));
    }

    @Order(8)
    @Test
    void trace_noData_returns40401() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/traces/t-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
        mockMvc.perform(get("/api/observe/customers/c-2/traces/t-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---------- span 详情（详设 §2.3）：全字段 + JSON 字段解析为对象 ----------

    @Order(9)
    @Test
    void spanDetail_returnsAllFieldsWithJsonParsedAsObjects() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/traces/t-1/spans/s-root"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.traceId").value("t-1"))
                .andExpect(jsonPath("$.data.spanId").value("s-root"))
                .andExpect(jsonPath("$.data.parentSpanId").value(""))
                .andExpect(jsonPath("$.data.traceState").value(""))
                .andExpect(jsonPath("$.data.name").value("biz.business.ORDER"))
                .andExpect(jsonPath("$.data.kind").value(1))
                .andExpect(jsonPath("$.data.startTime").value("2026-09-15T10:00:00Z"))
                .andExpect(jsonPath("$.data.endTime").value("2026-09-15T10:00:03Z"))
                .andExpect(jsonPath("$.data.statusCode").value(1))
                .andExpect(jsonPath("$.data.statusMessage").value(""))
                .andExpect(jsonPath("$.data.bizCode").value("ORDER"))
                .andExpect(jsonPath("$.data.serviceName").value("shop-web"))
                .andExpect(jsonPath("$.data.durationMs").value(3000.0))
                .andExpect(jsonPath("$.data.effectiveDurationMs").value(250.0))
                // JSON 字段解析为对象（非字符串）
                .andExpect(jsonPath("$.data.resource['service.version']").value("1.0"))
                .andExpect(jsonPath("$.data.scope.name").value("nl-observe"))
                .andExpect(jsonPath("$.data.attributes['biz.status']").value("success"))
                .andExpect(jsonPath("$.data.attributes['biz.total_suspended_ms']").value(100))
                // 单 span 详情 events 全字段（name/time/attributes）
                .andExpect(jsonPath("$.data.events.length()").value(2))
                .andExpect(jsonPath("$.data.events[0].name").value("biz.suspend_start"))
                .andExpect(jsonPath("$.data.events[0].time").value("2026-09-15T10:00:01Z"))
                .andExpect(jsonPath("$.data.events[0].attributes['biz.biz_id']").value("s-root"));
    }

    @Order(10)
    @Test
    void spanDetail_noData_returns40401() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/traces/t-1/spans/s-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
        // trace 存在但 span 属于另一 trace：三元组精确匹配
        mockMvc.perform(get("/api/observe/customers/c-1/traces/t-2/spans/s-root"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---------- metrics/summary（详设 §2.4，T11）：已知数据点 → P95/成功率与手工计算对拍 ----------

    @Order(11)
    @Test
    void metricsSummary_customerScope_p95AndSuccessRateMatchManualComputation() throws Exception {
        mockMvc.perform(get("/api/observe/metrics/summary")
                        .param("from", METRICS_FROM).param("to", METRICS_TO).param("customerId", "c-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // c-1 首屏 21 点 1810..1830：quantile(0.95)=第 20 小=1829.0（线性插值/最近秩同值）
                .andExpect(jsonPath("$.data.firstScreenLoadP95").value(FIRST_SCREEN_P95_MANUAL))
                .andExpect(jsonPath("$.data.perBiz.length()").value(2))
                // ORDER：21 root span（duration 100..300 步长 10）→ P95=第 20 小=290.0；20 success/21
                .andExpect(jsonPath("$.data.perBiz[0].bizCode").value("ORDER"))
                .andExpect(jsonPath("$.data.perBiz[0].effectiveDurationP95").value(ORDER_P95_MANUAL))
                .andExpect(jsonPath("$.data.perBiz[0].successRate").value(closeTo(ORDER_SUCCESS_COUNT / (double) ORDER_SPANS, 1e-9)))
                .andExpect(jsonPath("$.data.perBiz[0].count").value(ORDER_SPANS))
                // PAY：5 个全等值 1234.0 → 任意 quantile 口径 P95=1234.0；4 success/5=0.8
                .andExpect(jsonPath("$.data.perBiz[1].bizCode").value("PAY"))
                .andExpect(jsonPath("$.data.perBiz[1].effectiveDurationP95").value(PAY_DURATION))
                .andExpect(jsonPath("$.data.perBiz[1].successRate").value(closeTo(PAY_SUCCESS_COUNT / (double) PAY_SPANS, 1e-9)))
                .andExpect(jsonPath("$.data.perBiz[1].count").value(PAY_SPANS));
    }

    @Order(12)
    @Test
    void metricsSummary_customerScope_bizCodeFilter_onlyHitsThatBiz() throws Exception {
        mockMvc.perform(get("/api/observe/metrics/summary")
                        .param("from", METRICS_FROM).param("to", METRICS_TO)
                        .param("customerId", "c-1").param("bizCode", "PAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.perBiz.length()").value(1))
                .andExpect(jsonPath("$.data.perBiz[0].bizCode").value("PAY"))
                .andExpect(jsonPath("$.data.perBiz[0].count").value(PAY_SPANS));
    }

    @Order(13)
    @Test
    void metricsSummary_opsView_crossCustomerP95() throws Exception {
        // 不带 customerId → 跨客户运维视图：P95 须合并 c-2 的 2 个 1830.0 数据点（23 点 → 1830.0）；
        // 若误按单客户过滤仅得 c-1 的 1829.0 → 断言值差 1.0 可判别跨客户语义
        mockMvc.perform(get("/api/observe/metrics/summary")
                        .param("from", METRICS_FROM).param("to", METRICS_TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.firstScreenLoadP95").value(OPS_VIEW_P95_MANUAL))
                // c-2 无业务 span → perBiz 与 c-1 作用域一致（ORDER/PAY 聚合不变）
                .andExpect(jsonPath("$.data.perBiz.length()").value(2))
                .andExpect(jsonPath("$.data.perBiz[0].bizCode").value("ORDER"))
                .andExpect(jsonPath("$.data.perBiz[0].count").value(ORDER_SPANS))
                .andExpect(jsonPath("$.data.perBiz[1].bizCode").value("PAY"));
    }

    @Order(14)
    @Test
    void metricsSummary_opsView_overLimit_returns42901() throws Exception {
        // 限额 60 次/分（@SpringBootTest 属性）+ 固定时钟（见 FrozenClockRateLimiterConfig，评审 M-1）：
        // 窗口永不推进 → 连续 61 次运维视图调用，首个 429 必现于 61 次内，且首个 429 之后
        // 全部 429——判定与循环墙钟耗时无关（慢 CI 上 61×2 次真实 Databend 查询可能超 60s，
        // 真实时钟下会跨窗口清零导致一次 429 都不出现）。前序 Order(13) 至多消耗 1 次配额，不影响判定。
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            statuses.add(mockMvc.perform(get("/api/observe/metrics/summary")
                            .param("from", METRICS_FROM).param("to", METRICS_TO))
                    .andReturn().getResponse().getStatus());
        }
        int firstLimited = statuses.indexOf(429);
        assertThat(firstLimited).as("61 次运维视图调用内须触发限流（60 次/分）").isNotNegative().isLessThanOrEqualTo(60);
        assertThat(statuses.subList(firstLimited, statuses.size()))
                .as("首个 429 之后同一窗口内全部拒绝").containsOnly(429);
        // 429 响应体仍为统一 {code,message,data} 契约（42901）
        mockMvc.perform(get("/api/observe/metrics/summary")
                        .param("from", METRICS_FROM).param("to", METRICS_TO))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));
    }

    @Order(15)
    @Test
    void metricsSummary_missingFrom_returns40001() throws Exception {
        // 参数校验先于限流判定（QueryRange.parse 在 tryAcquire 之前）→ 不消耗运维视图配额
        mockMvc.perform(get("/api/observe/metrics/summary").param("to", METRICS_TO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Order(16)
    @Test
    void metricsSummary_noData_returns40401() throws Exception {
        // c-2 在 09 窗口无任何 metrics/span 数据（其预置均在 08 窗口）
        mockMvc.perform(get("/api/observe/metrics/summary")
                        .param("from", "2026-09-01").param("to", "2026-09-30").param("customerId", "c-2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    /**
     * 评审 M-1：以固定时间源替换运维视图限流 bean（@Primary 覆盖组件扫描实例）——
     * 60s 固定窗口永不滚动，Order(14) 的「61 次内首个 429 必现」断言不再依赖循环
     * 墙钟耗时；窗口滚动语义由 {@code OpsViewRateLimiterTest} 注入时钟单测钉住。
     */
    @TestConfiguration
    static class FrozenClockRateLimiterConfig {
        @Bean
        @Primary
        OpsViewRateLimiter frozenOpsViewRateLimiter(QueryProperties properties) {
            return new OpsViewRateLimiter(properties, () -> FROZEN_NOW_MS);
        }
    }

    private static String withPresignedDisabled(String jdbcUrl) {
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + DatabendProperties.REQUIRED_URL_PARAM;
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** 去掉语句前置的 -- 行注释（与 T09 IT 同口径）。 */
    private static String stripLeadingComments(String statement) {
        String[] lines = statement.split("\n");
        int first = 0;
        while (first < lines.length && lines[first].trim().startsWith("--")) {
            first++;
        }
        return String.join("\n", Arrays.copyOfRange(lines, first, lines.length));
    }
}

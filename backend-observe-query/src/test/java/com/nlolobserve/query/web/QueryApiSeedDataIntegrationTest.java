package com.nlolobserve.query.web;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;

import javax.sql.DataSource;

import com.nlolobserve.common.ddl.DdlScripts;
import com.nlolobserve.query.config.DatabendDataSourceConfig;
import com.nlolobserve.query.config.DatabendProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.databend.DatabendContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T10 验收（test_cases #1）：预置数据 → 三接口数据断言；参数错误/无数据 → 错误码断言。
 *
 * <p>与 {@code QueryApiTest}（T09，mock repo 契约层）互补：本类经 Testcontainers 真实 Databend
 * （生产 {@link DatabendDataSourceConfig} 只读池 + 启动 SELECT 1 自检）+ MockMvc 全栈
 * （Controller→Service→QueryRepository→Databend），断言 businesses / trace / span
 * 三端点对预置数据的真实返回形态（详设 §2.1/§2.2/§2.3）。
 *
 * <p>env-gated（详设 §6.2）：Docker 不可用或镜像不可得时按 assumption 跳过，不判失败。
 */
@SpringBootTest(properties = {
        "observe.ops-view-rate-limit-per-min=60",
        "logging.audit.file=target/audit/audit-api-seed-it.log"
})
@AutoConfigureMockMvc
class QueryApiSeedDataIntegrationTest {

    private static final String DEFAULT_IMAGE_NAME = "datafuselabs/databend:latest";

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

    // ---------- businesses（详设 §2.1）：预置数据 → 数据断言 ----------

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

    @Test
    void businesses_missingFromOrTo_returns40001() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses").param("to", "2026-09-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        mockMvc.perform(get("/api/observe/customers/c-1/businesses").param("from", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void businesses_spanOver31Days_returns40001() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-01").param("to", "2026-10-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void businesses_malformedDate_returns40001() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "20260901").param("to", "2026-09-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

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

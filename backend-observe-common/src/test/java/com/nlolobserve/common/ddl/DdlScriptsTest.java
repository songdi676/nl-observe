package com.nlolobserve.common.ddl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * T02/T03 验收：DdlScripts 含三表完整 DDL（Databend 方言：FUSE + CLUSTER BY（TTL 语义见 DdlScripts 类注释），
 * 去重身份 = 聚簇键、写入侧 REPLACE INTO 折叠；方言适配说明见 DdlScripts 类注释）。
 */
class DdlScriptsTest {

    @Test
    void tableNamesMatchDesign() {
        assertEquals("otel_spans", TableNames.OTEL_SPANS);
        assertEquals("otel_metrics", TableNames.OTEL_METRICS);
        assertEquals("otel_logs", TableNames.OTEL_LOGS);
    }

    @Test
    void spansDdlHasClusterAndKeyColumns() {
        String ddl = DdlScripts.OTEL_SPANS;
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS otel_spans"));
        assertTrue(ddl.contains("CLUSTER BY (customer_id, trace_id, span_id, start_time, end_time)"));
        for (String column : new String[] {"customer_id", "trace_id", "span_id", "parent_span_id",
                "trace_state", "name", "kind", "start_time", "end_time", "status_code", "status_message",
                "biz_code", "service_name", "effective_duration_ms", "resource_json", "scope_json", "attributes_json"}) {
            assertTrue(ddl.contains(column), "spans DDL 缺列: " + column);
        }
        assertTrue(ddl.contains("TIMESTAMP"), "时间列应为 Databend TIMESTAMP 类型");
        // 去重身份键（REPLACE INTO 键/聚簇键）列必须 NOT NULL（T03 评审 Critical 修复钉住）
        for (String notNull : new String[] {"customer_id        String        NOT NULL",
                "trace_id           String        NOT NULL",
                "span_id            String        NOT NULL",
                "start_time         TIMESTAMP NOT NULL",
                "end_time           TIMESTAMP NOT NULL"}) {
            assertTrue(ddl.contains(notNull), "spans 去重键列缺 NOT NULL: " + notNull);
        }
    }

    @Test
    void metricsDdlHasClusterAndKeyColumns() {
        String ddl = DdlScripts.OTEL_METRICS;
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS otel_metrics"));
        assertTrue(ddl.contains("CLUSTER BY (customer_id, metric_name, time, dp_hash)"));
        for (String column : new String[] {"customer_id", "metric_name", "metric_type", "time", "value",
                "biz_code", "resource_json", "attributes_json", "dp_hash"}) {
            assertTrue(ddl.contains(column), "metrics DDL 缺列: " + column);
        }
        // 去重键列 NOT NULL（同上）
        for (String notNull : new String[] {"customer_id  String        NOT NULL",
                "metric_name  String        NOT NULL",
                "time         TIMESTAMP NOT NULL",
                "dp_hash      String        NOT NULL"}) {
            assertTrue(ddl.contains(notNull), "metrics 去重键列缺 NOT NULL: " + notNull);
        }
    }

    @Test
    void logsDdlHasClusterAndKeyColumns() {
        String ddl = DdlScripts.OTEL_LOGS;
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS otel_logs"));
        assertTrue(ddl.contains("CLUSTER BY (customer_id, trace_id, time, row_hash)"));
        for (String column : new String[] {"customer_id", "trace_id", "span_id", "time", "severity_number",
                "severity_text", "body", "resource_json", "attributes_json", "row_hash"}) {
            assertTrue(ddl.contains(column), "logs DDL 缺列: " + column);
        }
        // 去重键列 NOT NULL（同上）
        for (String notNull : new String[] {"customer_id     String        NOT NULL",
                "trace_id        String NOT NULL DEFAULT ''",
                "time            TIMESTAMP NOT NULL",
                "row_hash        String        NOT NULL"}) {
            assertTrue(ddl.contains(notNull), "logs 去重键列缺 NOT NULL: " + notNull);
        }
    }

    @Test
    void initScriptContainsAllThreeTablesInOrder() {
        String script = DdlScripts.initScript();
        int spans = script.indexOf("CREATE TABLE IF NOT EXISTS otel_spans");
        int metrics = script.indexOf("CREATE TABLE IF NOT EXISTS otel_metrics");
        int logs = script.indexOf("CREATE TABLE IF NOT EXISTS otel_logs");
        assertTrue(spans >= 0 && metrics > spans && logs > metrics);
        // 语句间必须有分号（按 ; 切句逐条执行；缺分号会把两条 CREATE 拼成一条，实测 code=1005）
        assertEquals(3, script.chars().filter(c -> c == ';').count(), "initScript 应恰有 3 个语句终止分号");
    }

    @Test
    void columnsConstantsMatchTableNames() {
        assertEquals("customer_id", Columns.SPANS_CUSTOMER_ID);
        assertEquals("trace_id", Columns.SPANS_TRACE_ID);
        assertEquals("span_id", Columns.SPANS_SPAN_ID);
        assertEquals("effective_duration_ms", Columns.SPANS_EFFECTIVE_DURATION_MS);
        assertEquals("metric_name", Columns.METRICS_METRIC_NAME);
        assertEquals("dp_hash", Columns.METRICS_DP_HASH);
        assertEquals("row_hash", Columns.LOGS_ROW_HASH);
        assertEquals("severity_number", Columns.LOGS_SEVERITY_NUMBER);
    }
}

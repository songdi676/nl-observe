package com.nlolobserve.common.ddl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * T02 验收：DdlScripts 含三表完整 DDL（与概要设计 §6 一致：ReplacingMergeTree + 日分区 + TTL 90 天，
 * 去重身份 = ORDER BY 键）。
 */
class DdlScriptsTest {

    @Test
    void tableNamesMatchDesign() {
        assertEquals("otel_spans", TableNames.OTEL_SPANS);
        assertEquals("otel_metrics", TableNames.OTEL_METRICS);
        assertEquals("otel_logs", TableNames.OTEL_LOGS);
    }

    @Test
    void spansDdlHasEnginePartitionOrderTtlAndKeyColumns() {
        String ddl = DdlScripts.OTEL_SPANS;
        assertTrue(ddl.contains("CREATE TABLE otel_spans"));
        assertTrue(ddl.contains("ENGINE = ReplacingMergeTree"));
        assertTrue(ddl.contains("PARTITION BY toYYYYMMDD(start_time)"));
        assertTrue(ddl.contains("ORDER BY (customer_id, trace_id, span_id, start_time, end_time)"));
        assertTrue(ddl.contains("TTL start_time + INTERVAL 90 DAY"));
        for (String column : new String[] {"customer_id", "trace_id", "span_id", "parent_span_id",
                "trace_state", "name", "kind", "start_time", "end_time", "status_code", "status_message",
                "biz_code", "service_name", "effective_duration_ms", "resource_json", "scope_json", "attributes_json"}) {
            assertTrue(ddl.contains(column), "spans DDL 缺列: " + column);
        }
        assertTrue(ddl.contains("DateTime64(9)"));
    }

    @Test
    void metricsDdlHasEnginePartitionOrderTtlAndKeyColumns() {
        String ddl = DdlScripts.OTEL_METRICS;
        assertTrue(ddl.contains("CREATE TABLE otel_metrics"));
        assertTrue(ddl.contains("ENGINE = ReplacingMergeTree"));
        assertTrue(ddl.contains("PARTITION BY toYYYYMMDD(time)"));
        assertTrue(ddl.contains("ORDER BY (customer_id, metric_name, time, dp_hash)"));
        assertTrue(ddl.contains("TTL time + INTERVAL 90 DAY"));
        for (String column : new String[] {"customer_id", "metric_name", "metric_type", "time", "value",
                "biz_code", "resource_json", "attributes_json", "dp_hash"}) {
            assertTrue(ddl.contains(column), "metrics DDL 缺列: " + column);
        }
    }

    @Test
    void logsDdlHasEnginePartitionOrderTtlAndKeyColumns() {
        String ddl = DdlScripts.OTEL_LOGS;
        assertTrue(ddl.contains("CREATE TABLE otel_logs"));
        assertTrue(ddl.contains("ENGINE = ReplacingMergeTree"));
        assertTrue(ddl.contains("PARTITION BY toYYYYMMDD(time)"));
        assertTrue(ddl.contains("ORDER BY (customer_id, trace_id, time, row_hash)"));
        assertTrue(ddl.contains("TTL time + INTERVAL 90 DAY"));
        for (String column : new String[] {"customer_id", "trace_id", "span_id", "time", "severity_number",
                "severity_text", "body", "resource_json", "attributes_json", "row_hash"}) {
            assertTrue(ddl.contains(column), "logs DDL 缺列: " + column);
        }
    }

    @Test
    void initScriptContainsAllThreeTablesInOrder() {
        String script = DdlScripts.initScript();
        int spans = script.indexOf("CREATE TABLE otel_spans");
        int metrics = script.indexOf("CREATE TABLE otel_metrics");
        int logs = script.indexOf("CREATE TABLE otel_logs");
        assertTrue(spans >= 0 && metrics > spans && logs > metrics);
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

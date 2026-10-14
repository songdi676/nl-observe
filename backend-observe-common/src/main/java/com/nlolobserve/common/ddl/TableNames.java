package com.nlolobserve.common.ddl;

/**
 * Databend 三表表名（DDL 单一事实来源的一部分，详细设计 §3）。
 */
public final class TableNames {

    public static final String OTEL_SPANS = "otel_spans";
    public static final String OTEL_METRICS = "otel_metrics";
    public static final String OTEL_LOGS = "otel_logs";

    private TableNames() {
    }
}

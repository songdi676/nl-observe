package com.nlolobserve.common.constant;

import java.util.List;

/**
 * Kafka 三 topic 名（需求 yaml / 概要设计 §5.3），载荷 OTLP/JSON，key = nl.customer_id。
 */
public final class Topics {

    /** ExportTraceServiceRequest */
    public static final String TRACES = "nl-observe.otlp.traces";
    /** ExportMetricsServiceRequest */
    public static final String METRICS = "nl-observe.otlp.metrics";
    /** ExportLogsServiceRequest */
    public static final String LOGS = "nl-observe.otlp.logs";

    /** 全部 topic（接收端路由/测试断言用） */
    public static final List<String> ALL = List.of(TRACES, METRICS, LOGS);

    private Topics() {
    }
}

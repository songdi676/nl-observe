package com.nlolobserve.common.ddl;

/**
 * 三表列名常量（存储端 batch insert / 查询端 SELECT 统一引用，禁止散落字符串）。
 */
public final class Columns {

    public static final String SPANS_CUSTOMER_ID = "customer_id";
    public static final String SPANS_TRACE_ID = "trace_id";
    public static final String SPANS_SPAN_ID = "span_id";
    public static final String SPANS_PARENT_SPAN_ID = "parent_span_id";
    public static final String SPANS_TRACE_STATE = "trace_state";
    public static final String SPANS_NAME = "name";
    public static final String SPANS_KIND = "kind";
    public static final String SPANS_START_TIME = "start_time";
    public static final String SPANS_END_TIME = "end_time";
    public static final String SPANS_STATUS_CODE = "status_code";
    public static final String SPANS_STATUS_MESSAGE = "status_message";
    public static final String SPANS_BIZ_CODE = "biz_code";
    public static final String SPANS_SERVICE_NAME = "service_name";
    public static final String SPANS_EFFECTIVE_DURATION_MS = "effective_duration_ms";
    public static final String SPANS_RESOURCE_JSON = "resource_json";
    public static final String SPANS_SCOPE_JSON = "scope_json";
    public static final String SPANS_ATTRIBUTES_JSON = "attributes_json";
    public static final String SPANS_EVENTS_JSON = "events_json";

    public static final String METRICS_CUSTOMER_ID = "customer_id";
    public static final String METRICS_METRIC_NAME = "metric_name";
    public static final String METRICS_METRIC_TYPE = "metric_type";
    public static final String METRICS_TIME = "time";
    public static final String METRICS_VALUE = "value";
    public static final String METRICS_BIZ_CODE = "biz_code";
    public static final String METRICS_RESOURCE_JSON = "resource_json";
    public static final String METRICS_ATTRIBUTES_JSON = "attributes_json";
    public static final String METRICS_DP_HASH = "dp_hash";

    public static final String LOGS_CUSTOMER_ID = "customer_id";
    public static final String LOGS_TRACE_ID = "trace_id";
    public static final String LOGS_SPAN_ID = "span_id";
    public static final String LOGS_TIME = "time";
    public static final String LOGS_SEVERITY_NUMBER = "severity_number";
    public static final String LOGS_SEVERITY_TEXT = "severity_text";
    public static final String LOGS_BODY = "body";
    public static final String LOGS_RESOURCE_JSON = "resource_json";
    public static final String LOGS_ATTRIBUTES_JSON = "attributes_json";
    public static final String LOGS_ROW_HASH = "row_hash";

    private Columns() {
    }
}

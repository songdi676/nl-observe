package com.nlolobserve.common.ddl;

/**
 * 三表完整 DDL 常量——DDL 唯一事实来源（详细设计 §3：scripts/databend/001-init.sql 由此生成/同步）。
 * 与概要设计 §6 一致：ReplacingMergeTree（去重身份 = ORDER BY 键）、日分区、TTL 90 天（假设，待澄清）。
 */
public final class DdlScripts {

    /** spans：去重键 = 全 ORDER BY 键（同 span 重复上报折叠）。
     *  ORDER BY（merge key）列按 Databend ReplacingMergeTree 要求声明 NOT NULL（身份列本就不应为空）。 */
    public static final String OTEL_SPANS = """
            CREATE TABLE IF NOT EXISTS otel_spans (
              customer_id        String        NOT NULL,
              trace_id           String        NOT NULL,
              span_id            String        NOT NULL,
              parent_span_id     String  DEFAULT '',
              trace_state        String  DEFAULT '',
              name               String,
              kind               UInt8,
              start_time         DateTime64(9) NOT NULL,
              end_time           DateTime64(9) NOT NULL,
              status_code        UInt8,
              status_message     String  DEFAULT '',
              biz_code           String  DEFAULT '',
              service_name       String,
              effective_duration_ms Float64 DEFAULT 0,
              resource_json      String,
              scope_json         String,
              attributes_json    String
            ) ENGINE = ReplacingMergeTree
            PARTITION BY toYYYYMMDD(start_time)
            ORDER BY (customer_id, trace_id, span_id, start_time, end_time)
            TTL start_time + INTERVAL 90 DAY;
            """;

    /** metrics：dp_hash 使不同数据点互不折叠、同键重复折叠；查询前缀 (customer_id, metric_name) 命中排序键 */
    public static final String OTEL_METRICS = """
            CREATE TABLE IF NOT EXISTS otel_metrics (
              customer_id  String        NOT NULL,
              metric_name  String        NOT NULL,
              metric_type  UInt8,
              time         DateTime64(9) NOT NULL,
              value        Float64,
              biz_code     String DEFAULT '',
              resource_json String,
              attributes_json String,
              dp_hash      String        NOT NULL
            ) ENGINE = ReplacingMergeTree
            PARTITION BY toYYYYMMDD(time)
            ORDER BY (customer_id, metric_name, time, dp_hash)
            TTL time + INTERVAL 90 DAY;
            """;

    /** logs：预留业务系统日志；行键折叠重复 */
    public static final String OTEL_LOGS = """
            CREATE TABLE IF NOT EXISTS otel_logs (
              customer_id     String        NOT NULL,
              trace_id        String NOT NULL DEFAULT '',
              span_id         String DEFAULT '',
              time            DateTime64(9) NOT NULL,
              severity_number UInt32,
              severity_text   String DEFAULT '',
              body            String,
              resource_json   String,
              attributes_json String,
              row_hash        String        NOT NULL
            ) ENGINE = ReplacingMergeTree
            PARTITION BY toYYYYMMDD(time)
            ORDER BY (customer_id, trace_id, time, row_hash)
            TTL time + INTERVAL 90 DAY;
            """;

    /** 建库初始化脚本（T03 生成 scripts/databend/001-init.sql 的事实来源） */
    public static String initScript() {
        return "-- nl-observe Databend 初始化（来源: backend-observe-common ddl/DdlScripts）\n"
                + OTEL_SPANS + "\n" + OTEL_METRICS + "\n" + OTEL_LOGS;
    }

    private DdlScripts() {
    }
}

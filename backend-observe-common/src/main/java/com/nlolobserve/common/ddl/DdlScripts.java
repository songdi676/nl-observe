package com.nlolobserve.common.ddl;

/**
 * 三表完整 DDL 常量——DDL 唯一事实来源（详细设计 §3：scripts/databend/001-init.sql 由此生成/同步）。
 *
 * 与概要设计 §6 的方言差异（T03 集成测试对真实 Databend 实跑发现，IT 为准）：
 * 概要设计按 ClickHouse 方言书写（ReplacingMergeTree/DateTime64(9)/PARTITION BY toYYYYMMDD），
 * Databend 均不支持（ENGINE 仅 FUSE 等）。适配映射：
 * - ENGINE = ReplacingMergeTree → FUSE（默认引擎，省略 ENGINE 子句）
 * - ORDER BY（merge key）→ CLUSTER BY（FUSE 聚簇键，查询前缀命中）
 * - DateTime64(9) → TIMESTAMP（纳秒精度，语义等价）
 * - PARTITION BY toYYYYMMDD(...) → 移除（FUSE 无显式分区，聚簇承担局部性）
 * - 去重（原 ReplacingMergeTree 折叠语义）→ 写入侧 REPLACE INTO（T08），
 *   去重身份键 = 原 ORDER BY 键（列保持 NOT NULL，REPLACE INTO 键列不得为空）；
 *   方言实测（Databend v1.2.881，T08）：REPLACE INTO 必须显式 {@code ON (身份键)} 子句
 *   （键 = CLUSTER BY 键），缺失 ON 子句解析失败（code=1005 expecting ON）
 * - TTL 90 天 → 移除：本版本 Databend 的 FUSE 引擎不接受 TTL 表选项（实测 code=1301），
 *   且其 TTL 语义为表级保留期而非行级过期——概要设计"90 天后自动删除"（假设，待澄清）
 *   无原生 DDL 支持，由存储侧定期清理任务承担（T08/运维范围）。
 */
public final class DdlScripts {

    /**
     * spans：去重身份键（REPLACE INTO 键）= 全聚簇键（同 span 重复上报折叠）。
     *
     * events_json 契约（T10 评审 Critical：AC-2 span 树须含 events）：OTLP span events 数组，
     * 查询端原生形态 {@code [{"name":"...","time":"ISO-8601 UTC","attributes":{扁平键值}}]}，
     * 与 SpanNode.EventNode 一一对应。存储写路径从接收端 Kafka 载荷转换
     * （timeUnixNano→ISO-8601 UTC、attributes KeyValue 数组→扁平 map，与 attributes_json 同形态）；
     * 无事件写 '[]'（列缺省）。
     */
    public static final String OTEL_SPANS = """
            CREATE TABLE IF NOT EXISTS otel_spans (
              customer_id        String        NOT NULL,
              trace_id           String        NOT NULL,
              span_id            String        NOT NULL,
              parent_span_id     String  DEFAULT '',
              trace_state        String  DEFAULT '',
              name               String,
              kind               UInt8,
              start_time         TIMESTAMP NOT NULL,
              end_time           TIMESTAMP NOT NULL,
              status_code        UInt8,
              status_message     String  DEFAULT '',
              biz_code           String  DEFAULT '',
              service_name       String,
              effective_duration_ms Float64 DEFAULT 0,
              resource_json      String,
              scope_json         String,
              attributes_json    String,
              events_json        String  DEFAULT '[]'
            )
            CLUSTER BY (customer_id, trace_id, span_id, start_time, end_time)
            """;

    /** metrics：dp_hash 使不同数据点互不折叠、同键重复折叠；查询前缀 (customer_id, metric_name) 命中聚簇键 */
    public static final String OTEL_METRICS = """
            CREATE TABLE IF NOT EXISTS otel_metrics (
              customer_id  String        NOT NULL,
              metric_name  String        NOT NULL,
              metric_type  UInt8,
              time         TIMESTAMP NOT NULL,
              value        Float64,
              biz_code     String DEFAULT '',
              resource_json String,
              attributes_json String,
              dp_hash      String        NOT NULL
            )
            CLUSTER BY (customer_id, metric_name, time, dp_hash)
            """;

    /** logs：预留业务系统日志；去重身份键 = 聚簇键 */
    public static final String OTEL_LOGS = """
            CREATE TABLE IF NOT EXISTS otel_logs (
              customer_id     String        NOT NULL,
              trace_id        String NOT NULL DEFAULT '',
              span_id         String DEFAULT '',
              time            TIMESTAMP NOT NULL,
              severity_number UInt32,
              severity_text   String DEFAULT '',
              body            String,
              resource_json   String,
              attributes_json String,
              row_hash        String        NOT NULL
            )
            CLUSTER BY (customer_id, trace_id, time, row_hash)
            """;

    /**
     * 建库初始化脚本（T03 生成 scripts/databend/001-init.sql 的事实来源）。
     * 各表 DDL 以 {@code ;} 分隔：脚本按分号切句逐条执行（集成测试/运维客户端同理），
     * 缺分号会把相邻两条 CREATE 拼成一条语句致解析失败（实测 code=1005）。
     */
    public static String initScript() {
        return "-- nl-observe Databend 初始化（来源: backend-observe-common ddl/DdlScripts）\n"
                + OTEL_SPANS + ";\n" + OTEL_METRICS + ";\n" + OTEL_LOGS + ";";
    }

    private DdlScripts() {
    }
}

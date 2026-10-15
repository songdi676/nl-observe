-- nl-observe Databend 初始化（来源: backend-observe-common ddl/DdlScripts）
CREATE TABLE otel_spans (
  customer_id        String,
  trace_id           String,
  span_id            String,
  parent_span_id     String  DEFAULT '',
  trace_state        String  DEFAULT '',
  name               String,
  kind               UInt8,
  start_time         DateTime64(9),
  end_time           DateTime64(9),
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

CREATE TABLE otel_metrics (
  customer_id  String,
  metric_name  String,
  metric_type  UInt8,
  time         DateTime64(9),
  value        Float64,
  biz_code     String DEFAULT '',
  resource_json String,
  attributes_json String,
  dp_hash      String
) ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMMDD(time)
ORDER BY (customer_id, metric_name, time, dp_hash)
TTL time + INTERVAL 90 DAY;

CREATE TABLE otel_logs (
  customer_id     String,
  trace_id        String DEFAULT '',
  span_id         String DEFAULT '',
  time            DateTime64(9),
  severity_number UInt32,
  severity_text   String DEFAULT '',
  body            String,
  resource_json   String,
  attributes_json String,
  row_hash        String
) ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMMDD(time)
ORDER BY (customer_id, trace_id, time, row_hash)
TTL time + INTERVAL 90 DAY;

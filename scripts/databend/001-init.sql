-- nl-observe Databend 初始化（来源: backend-observe-common ddl/DdlScripts）
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
  attributes_json    String
)
CLUSTER BY (customer_id, trace_id, span_id, start_time, end_time)
;
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
;
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
;
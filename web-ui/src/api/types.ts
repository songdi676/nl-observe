// 与详细设计 §2 响应契约对齐（backend-observe-common dto 的前端镜像）

/** 统一响应包裹 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** §2.1 业务列表行 */
export interface BusinessItem {
  bizId: string
  bizCode: string
  traceId: string
  startTime: string
  effectiveDurationMs: number
  status: string
  endReason: string
}

/** span 事件（biz.suspend_start / biz.resume 等；契约：{name,time:ISO-8601 UTC,attributes}，
 *  与 backend-observe-common SpanNode.EventNode 一致） */
export interface SpanEvent {
  name: string
  time?: string | null
  attributes?: Record<string, unknown>
}

/** §2.2 span 节点 */
export interface SpanNode {
  spanId: string
  parentSpanId: string
  name: string
  kind: number
  startTime: string
  endTime: string
  statusCode: number
  durationMs: number
  effectiveDurationMs?: number
  attributes: Record<string, unknown>
  events?: SpanEvent[]
}

/** §2.2 trace（span 列表，前端按 parentSpanId 组树） */
export interface SpanTree {
  traceId: string
  spans: SpanNode[]
}

/** §2.3 单 span 全字段（JSON 列已解析为对象；字段与 backend SpanDetail DTO 对齐） */
export interface SpanDetail extends SpanNode {
  traceId: string
  traceState?: string | null
  statusMessage?: string | null
  bizCode?: string | null
  serviceName?: string | null
  resource: Record<string, unknown>
  scope: Record<string, unknown>
}

/** §2.4 单业务指标 */
export interface BizMetric {
  bizCode: string
  effectiveDurationP95: number
  successRate: number
  count: number
}

/** §2.4 指标汇总（firstScreenLoadP95 可空：后端 Double，无首屏数据时为 null，
 *  与 backend-observe-common MetricSummary DTO 一致） */
export interface MetricSummary {
  firstScreenLoadP95: number | null
  perBiz: BizMetric[]
}

/** from/to 必填（yyyy-MM-dd，跨度 ≤31 天），bizCode 可选 */
export interface TimeRangeQuery {
  from: string
  to: string
  bizCode?: string
}

/** §2.4 metrics/summary：恒带 customerId（验收口径——客户作用域查询路径，规避
 *  跨客户运维视图限流桶 60 次/分、超限 42901）。必填性钉进编译器（评审 arch M-1）：
 *  任何调用方漏传将落入运维视图桶，恰是 MetricsPage 刻意规避的行为 */
export interface MetricsSummaryQuery extends TimeRangeQuery {
  customerId: string
}

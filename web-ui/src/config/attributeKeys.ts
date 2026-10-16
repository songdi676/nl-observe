/**
 * 属性/事件键常量（backend-observe-common AttributeKeys 的前端镜像，
 * 白名单与 REST_SPAN_WHITELIST 一一对应；详细设计 §1.1/§7）。
 */
export const SPAN_ATTRIBUTE_KEYS = {
  NL_CUSTOMER_ID: 'nl.customer_id',
  NL_DURATION_MS: 'nl.duration_ms',
  BIZ_STATUS: 'biz.status',
  BIZ_END_REASON: 'biz.end_reason',
  BIZ_TOTAL_SUSPENDED_MS: 'biz.total_suspended_ms',
  BIZ_EFFECTIVE_DURATION_MS: 'biz.effective_duration_ms',
  BIZ_CODE: 'biz.code',
  BIZ_TRACE_ID: 'biz.trace_id',
  HTTP_REQUEST_METHOD: 'http.request.method',
  HTTP_ROUTE: 'http.route',
  HTTP_RESPONSE_STATUS_CODE: 'http.response.status_code'
} as const

/** 业务 root span 名称前缀：biz.business.&lt;bizCode&gt; */
export const BIZ_BUSINESS_NAME_PREFIX = 'biz.business.'

/** span event：挂起点 / 恢复点（时间线标记，AC-09 流程可视） */
export const BIZ_SPAN_EVENTS = {
  SUSPEND_START: 'biz.suspend_start',
  RESUME: 'biz.resume'
} as const

/** REST span 属性白名单（AC-04：仅 4 项，与后端 AttributeKeys.REST_SPAN_WHITELIST 一致） */
export const REST_SPAN_WHITELIST: readonly string[] = [
  SPAN_ATTRIBUTE_KEYS.HTTP_REQUEST_METHOD,
  SPAN_ATTRIBUTE_KEYS.HTTP_ROUTE,
  SPAN_ATTRIBUTE_KEYS.HTTP_RESPONSE_STATUS_CODE,
  SPAN_ATTRIBUTE_KEYS.NL_DURATION_MS
]

/** 时间线上需要打标记的挂起/恢复事件名 */
export const TIMELINE_EVENT_NAMES: readonly string[] = [
  BIZ_SPAN_EVENTS.SUSPEND_START,
  BIZ_SPAN_EVENTS.RESUME
]

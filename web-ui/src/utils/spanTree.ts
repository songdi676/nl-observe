import { BIZ_BUSINESS_NAME_PREFIX, SPAN_ATTRIBUTE_KEYS } from '../config/attributeKeys'
import type { SpanEvent, SpanNode } from '../api/types'

/** 组树节点：span + 子节点（时间线按深度缩进渲染） */
export interface SpanTreeNode {
  span: SpanNode
  children: SpanTreeNode[]
}

export interface TimelineDomain {
  startMs: number
  endMs: number
}

/** ISO-8601 → epoch ms；无效返回 NaN */
export function toEpochMs(iso: string | null | undefined): number {
  if (!iso) return Number.NaN
  const ms = Date.parse(iso)
  return Number.isNaN(ms) ? Number.NaN : ms
}

function spanStart(span: SpanNode): number {
  return toEpochMs(span.startTime)
}

function spanEnd(span: SpanNode): number {
  const end = toEpochMs(span.endTime)
  if (!Number.isNaN(end)) return end
  const start = spanStart(span)
  if (Number.isNaN(start)) return Number.NaN
  return start + (span.durationMs ?? 0)
}

/**
 * 平铺 span 列表（详设 §2.2：后端返回带 parentSpanId 的平铺数组）→ 嵌套树。
 * - parentSpanId 为空/缺失/成环 → 视为根（不丢 span）
 * - 根与子节点均按 startTime 升序
 */
export function buildSpanTree(spans: SpanNode[]): SpanTreeNode[] {
  const nodes = new Map<string, SpanTreeNode>()
  const unique: SpanNode[] = []
  for (const span of spans) {
    // 契约上 spanId 唯一；重复 id 保留首个并告警（后写覆盖会静默丢 span、
    // 且同一节点被挂载两次——评审 m-5）
    if (nodes.has(span.spanId)) {
      console.warn(`buildSpanTree：重复 spanId ${span.spanId}，保留首个、忽略后续重复项`)
      continue
    }
    nodes.set(span.spanId, { span, children: [] })
    unique.push(span)
  }
  const roots: SpanTreeNode[] = []
  for (const span of unique) {
    const node = nodes.get(span.spanId) as SpanTreeNode
    const parent = span.parentSpanId ? nodes.get(span.parentSpanId) : undefined
    if (!parent || parent === node || isAncestorCycle(node, parent, nodes)) {
      roots.push(node)
    } else {
      parent.children.push(node)
    }
  }
  const byStart = (a: SpanTreeNode, b: SpanTreeNode): number =>
    (spanStart(a.span) || 0) - (spanStart(b.span) || 0)
  roots.sort(byStart)
  for (const node of nodes.values()) {
    node.children.sort(byStart)
  }
  return roots
}

/**
 * 沿 parent 链上溯，遇到自身或已访问节点即判为环（该 span 提根，避免渲染死循环）。
 * 仅识别"经过自身"的环不完整：链进入一个不含自身的上游环（A↔B，另有 C→B）时
 * 上溯永不终止——评审 Major-1/安全 F-1（3 条畸形 span 即可冻结浏览器标签页）。
 * visited-set 保证上溯步数 ≤ 节点数，任意畸形 parent 图均终止。
 */
function isAncestorCycle(
  node: SpanTreeNode,
  parent: SpanTreeNode,
  nodes: Map<string, SpanTreeNode>
): boolean {
  const seen = new Set<SpanTreeNode>()
  let cur: SpanTreeNode | undefined = parent
  while (cur) {
    if (cur === node || seen.has(cur)) return true
    seen.add(cur)
    const pid: string = cur.span.parentSpanId
    cur = pid ? nodes.get(pid) : undefined
  }
  return false
}

/** 瀑布时间轴全域：min(startTime) → max(endTime)；空列表 → {0,0} */
export function timelineDomain(spans: SpanNode[]): TimelineDomain {
  let startMs = Number.POSITIVE_INFINITY
  let endMs = Number.NEGATIVE_INFINITY
  for (const span of spans) {
    const s = spanStart(span)
    const e = spanEnd(span)
    if (!Number.isNaN(s)) startMs = Math.min(startMs, s)
    if (!Number.isNaN(e)) endMs = Math.max(endMs, e)
  }
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs)) {
    return { startMs: 0, endMs: 0 }
  }
  return { startMs, endMs }
}

/** 业务 root span：name 前缀 biz.business.<bizCode> */
export function isBusinessRootSpan(span: SpanNode): boolean {
  return span.name.startsWith(BIZ_BUSINESS_NAME_PREFIX)
}

/** REST span：属性含 http.* 白名单键（SDK autoprofile 仅写白名单 4 项；
 *  判定只看 3 个 http.* 键，nl.duration_ms 仅作白名单展示——评审 n-3 点明分歧） */
export function isRestSpan(span: SpanNode): boolean {
  const attrs = span.attributes ?? {}
  return (
    SPAN_ATTRIBUTE_KEYS.HTTP_REQUEST_METHOD in attrs ||
    SPAN_ATTRIBUTE_KEYS.HTTP_ROUTE in attrs ||
    SPAN_ATTRIBUTE_KEYS.HTTP_RESPONSE_STATUS_CODE in attrs
  )
}

/** span 条在时间轴上的 left/width 百分比（全域为 0 → 满宽占位） */
export function barGeometry(
  span: SpanNode,
  domain: TimelineDomain
): { leftPct: number; widthPct: number } {
  const total = domain.endMs - domain.startMs
  if (total <= 0) {
    return { leftPct: 0, widthPct: 100 }
  }
  const start = spanStart(span)
  const end = spanEnd(span)
  const left = Number.isNaN(start) ? 0 : ((start - domain.startMs) / total) * 100
  const width =
    Number.isNaN(start) || Number.isNaN(end) ? 0 : ((end - start) / total) * 100
  return {
    leftPct: Math.min(100, Math.max(0, left)),
    widthPct: Math.min(100, Math.max(0, width))
  }
}

/**
 * 挂起/恢复事件在 span 条内的百分比位置（相对 span 自身起止）。
 * 事件 time 缺失/无法解析 → null（渲染侧不画标记，评审 m-3：与"贴条首"语义区分）；
 * 事件 time 有效但 span 时长为 0 → 0（贴条首）。
 */
export function eventMarkerPct(event: SpanEvent | undefined, span: SpanNode): number | null {
  if (!event) return null
  const t = toEpochMs(event.time)
  if (Number.isNaN(t)) return null
  const start = spanStart(span)
  const end = spanEnd(span)
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return 0
  return Math.min(100, Math.max(0, ((t - start) / (end - start)) * 100))
}

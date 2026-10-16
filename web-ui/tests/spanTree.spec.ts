import { describe, expect, it } from 'vitest'
import {
  barGeometry,
  buildSpanTree,
  eventMarkerPct,
  isBusinessRootSpan,
  isRestSpan,
  timelineDomain,
  type SpanTreeNode
} from '../src/utils/spanTree'
import type { SpanNode } from '../src/api/types'

const span = (p: Partial<SpanNode> & { spanId: string }): SpanNode => ({
  parentSpanId: '',
  name: p.spanId,
  kind: 0,
  startTime: '2026-09-28T09:00:00.000Z',
  endTime: '2026-09-28T09:00:01.000Z',
  statusCode: 0,
  durationMs: 1000,
  attributes: {},
  ...p
})

const bizRoot = span({
  spanId: 's1',
  name: 'biz.business.ORDER',
  startTime: '2026-09-28T09:00:00.000Z',
  endTime: '2026-09-28T09:00:45.210Z',
  durationMs: 45210,
  effectiveDurationMs: 45210,
  attributes: { 'biz.status': 'success' },
  events: [
    { name: 'biz.suspend_start', time: '2026-09-28T09:00:10.000Z' },
    { name: 'biz.resume', time: '2026-09-28T09:00:20.000Z' }
  ]
})

const rest1 = span({
  spanId: 's2',
  parentSpanId: 's1',
  name: 'GET /api/orders',
  kind: 2,
  startTime: '2026-09-28T09:00:05.000Z',
  endTime: '2026-09-28T09:00:06.000Z',
  durationMs: 1000,
  attributes: {
    'http.request.method': 'GET',
    'http.route': '/api/orders',
    'http.response.status_code': 200,
    'nl.duration_ms': 1000
  }
})

const rest2 = span({
  spanId: 's3',
  parentSpanId: 's1',
  name: 'POST /api/orders',
  kind: 2,
  startTime: '2026-09-28T09:00:30.000Z',
  endTime: '2026-09-28T09:00:31.000Z',
  durationMs: 1000,
  attributes: {
    'http.request.method': 'POST',
    'http.route': '/api/orders',
    'http.response.status_code': 201,
    'nl.duration_ms': 1000
  }
})

const orphan = span({
  spanId: 's4',
  parentSpanId: 'missing-parent',
  name: 'orphan.span',
  startTime: '2026-09-28T09:00:40.000Z',
  endTime: '2026-09-28T09:00:41.000Z',
  durationMs: 1000
})

const FLAT = [orphan, rest2, bizRoot, rest1] // 乱序输入

describe('buildSpanTree：平铺 span 列表按 parentSpanId 组树', () => {
  it('业务 root span 与 REST span 层级正确（REST 为业务 root 子节点）', () => {
    const tree = buildSpanTree(FLAT)
    const rootIds = tree.map((n) => n.span.spanId)
    expect(rootIds.sort()).toEqual(['s1', 's4'])
    const s1 = tree.find((n) => n.span.spanId === 's1') as SpanTreeNode
    expect(s1.children.map((c) => c.span.spanId).sort()).toEqual(['s2', 's3'])
    expect(s1.children.every((c) => c.children.length === 0)).toBe(true)
  })

  it('父节点缺失（孤儿 span）提升为根，不丢失、不成环', () => {
    const tree = buildSpanTree(FLAT)
    const orphanNode = tree.find((n) => n.span.spanId === 's4')
    expect(orphanNode).toBeDefined()
    expect(orphanNode?.children).toEqual([])
  })

  it('子节点按 startTime 升序（输入乱序仍有序）', () => {
    const tree = buildSpanTree(FLAT)
    const s1 = tree.find((n) => n.span.spanId === 's1') as SpanTreeNode
    const starts = s1.children.map((c) => c.span.startTime)
    expect(starts).toEqual([...starts].sort())
    expect(s1.children[0].span.spanId).toBe('s2')
    expect(s1.children[1].span.spanId).toBe('s3')
  })

  it('自引用 parentSpanId 不导致死循环（视为根）', () => {
    const selfRef = span({ spanId: 'x', parentSpanId: 'x' })
    const tree = buildSpanTree([selfRef])
    expect(tree.map((n) => n.span.spanId)).toEqual(['x'])
  })

  it('上游两两成环（A↔B）且另有 C→B：全部提根终止，不死循环（评审 Major-1/安全 F-1）', () => {
    const a = span({ spanId: 'a', parentSpanId: 'b' })
    const b = span({ spanId: 'b', parentSpanId: 'a' })
    const c = span({ spanId: 'c', parentSpanId: 'b' })
    const tree = buildSpanTree([a, b, c])
    // C 的 parent 链进入不含自身的环——旧实现 while 同步死循环冻结标签页
    expect(tree.map((n) => n.span.spanId).sort()).toEqual(['a', 'b', 'c'])
    for (const n of tree) expect(n.children).toEqual([])
  })

  it('自环被他节点引用（X.parent=X, Y.parent=X）：均提根终止', () => {
    const x = span({ spanId: 'x', parentSpanId: 'x' })
    const y = span({ spanId: 'y', parentSpanId: 'x' })
    const tree = buildSpanTree([x, y])
    expect(tree.map((n) => n.span.spanId).sort()).toEqual(['x', 'y'])
  })

  it('重复 spanId：保留首个、忽略后续，不重复挂载（评审 m-5）', () => {
    const first = span({ spanId: 'd', name: 'first' })
    const second = span({ spanId: 'd', name: 'second' })
    const tree = buildSpanTree([first, second])
    expect(tree).toHaveLength(1)
    expect(tree[0].span.name).toBe('first')
    expect(tree[0].children).toEqual([])
  })

  it('空列表 → 空树', () => {
    expect(buildSpanTree([])).toEqual([])
  })
})

describe('timelineDomain：瀑布时间轴全域', () => {
  it('start=最早 startTime，end=最晚 endTime', () => {
    const d = timelineDomain(FLAT)
    expect(d.startMs).toBe(Date.parse('2026-09-28T09:00:00.000Z'))
    expect(d.endMs).toBe(Date.parse('2026-09-28T09:00:45.210Z'))
  })

  it('endTime 缺失时以 startTime+durationMs 兜底', () => {
    const d = timelineDomain([span({ spanId: 'a', endTime: '', durationMs: 500 })])
    expect(d.endMs - d.startMs).toBe(500)
  })

  it('空列表 → 全域为 0（渲染侧应短路）', () => {
    const d = timelineDomain([])
    expect(d).toEqual({ startMs: 0, endMs: 0 })
  })
})

describe('span 类型判定', () => {
  it('isBusinessRootSpan：仅 biz.business. 前缀', () => {
    expect(isBusinessRootSpan(bizRoot)).toBe(true)
    expect(isBusinessRootSpan(rest1)).toBe(false)
  })
  it('isRestSpan：含 http.* 白名单属性', () => {
    expect(isRestSpan(rest1)).toBe(true)
    expect(isRestSpan(bizRoot)).toBe(false)
    expect(isRestSpan(span({ spanId: 'p', name: 'GET /x', attributes: { 'http.route': '/x' } }))).toBe(true)
  })
})

describe('barGeometry：瀑布条 left/width 百分比', () => {
  const domain = timelineDomain(FLAT)
  const total = domain.endMs - domain.startMs
  it('REST span 位置 = (spanStart-domainStart)/全域，宽度 = 时长/全域', () => {
    const g = barGeometry(rest1, domain)
    expect(g.leftPct).toBeCloseTo((5000 / total) * 100, 6)
    expect(g.widthPct).toBeCloseTo((1000 / total) * 100, 6)
  })
  it('全域为 0 → 满宽占位', () => {
    expect(barGeometry(rest1, { startMs: 0, endMs: 0 })).toEqual({ leftPct: 0, widthPct: 100 })
  })
})

describe('eventMarkerPct：挂起/恢复事件在 span 条内的百分比位置', () => {
  it('biz.suspend_start @10s / 45.21s span ≈ 22.12%', () => {
    const ev = bizRoot.events?.[0]
    expect(eventMarkerPct(ev, bizRoot)).toBeCloseTo((10000 / 45210) * 100, 6)
  })
  it('span 时长为 0 → 0%（贴条首）', () => {
    const zero = span({
      spanId: 'z',
      endTime: '',
      durationMs: 0,
      events: [{ name: 'biz.suspend_start', time: '2026-09-28T09:00:00.000Z' }]
    })
    expect(eventMarkerPct(zero.events?.[0], zero)).toBe(0)
  })
  it('事件缺 time → null（渲染侧不画位置标记）', () => {
    expect(eventMarkerPct({ name: 'biz.resume' }, bizRoot)).toBeNull()
  })
})

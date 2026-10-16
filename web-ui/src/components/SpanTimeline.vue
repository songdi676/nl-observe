<script setup lang="ts">
import { computed } from 'vue'
import type { SpanEvent, SpanNode } from '../api/types'
import { SPAN_ATTRIBUTE_KEYS, TIMELINE_EVENT_NAMES } from '../config/attributeKeys'
import {
  barGeometry,
  buildSpanTree,
  eventMarkerPct,
  isBusinessRootSpan,
  isRestSpan,
  timelineDomain,
  type SpanTreeNode
} from '../utils/spanTree'

const props = defineProps<{ spans: SpanNode[] }>()
const emit = defineEmits<{ select: [span: SpanNode] }>()

interface TimelineRow {
  span: SpanNode
  depth: number
}

/** 组树后 DFS 展平（深度用于缩进），保证业务 root 与 REST 子 span 的层级可视 */
const rows = computed<TimelineRow[]>(() => {
  const out: TimelineRow[] = []
  const walk = (node: SpanTreeNode, depth: number): void => {
    out.push({ span: node.span, depth })
    for (const child of node.children) walk(child, depth + 1)
  }
  for (const root of buildSpanTree(props.spans)) walk(root, 0)
  return out
})

const domain = computed(() => timelineDomain(props.spans))

/** 时间线事件标记：白名单事件名 + 位置可解析（time 缺失/非法 → null → 不渲染，
 *  不得贴条首谎称发生在起点——评审 m-3） */
function markerEvents(span: SpanNode): { ev: SpanEvent; left: string }[] {
  return (span.events ?? [])
    .filter((e) => TIMELINE_EVENT_NAMES.includes(e.name))
    .map((ev) => ({ ev, pct: eventMarkerPct(ev, span) }))
    .filter((m): m is { ev: SpanEvent; pct: number } => m.pct !== null)
    .map((m) => ({ ev: m.ev, left: `${m.pct}%` }))
}

/** REST span 白名单属性摘要（method route · status · duration） */
function restSummary(span: SpanNode): string {
  const attrs = span.attributes ?? {}
  const method = String(attrs[SPAN_ATTRIBUTE_KEYS.HTTP_REQUEST_METHOD] ?? '')
  const route = String(attrs[SPAN_ATTRIBUTE_KEYS.HTTP_ROUTE] ?? '')
  const status = String(attrs[SPAN_ATTRIBUTE_KEYS.HTTP_RESPONSE_STATUS_CODE] ?? '')
  return [`${method} ${route}`.trim(), status, `${span.durationMs}ms`]
    .filter(Boolean)
    .join(' · ')
}

function barStyle(span: SpanNode): Record<string, string> {
  const g = barGeometry(span, domain.value)
  return { left: `${g.leftPct}%`, width: `${Math.max(g.widthPct, 0.3)}%` }
}

function rowClass(span: SpanNode): Record<string, boolean> {
  return {
    'span-row--biz': isBusinessRootSpan(span),
    'span-row--rest': isRestSpan(span),
    // 存储的是 OTLP Status code（0=UNSET,1=OK,2=ERROR，OtlpJsonParser 原样入库）；
    // 按"非 0 即错"会把 OK(1) 染红、且真实数据恒 UNSET(0) 永不标红——评审 M-2
    'span-row--error': span.statusCode === 2
  }
}

const INDENT_PX = 18
</script>

<template>
  <div class="span-timeline">
    <p
      v-if="rows.length === 0"
      class="empty"
    >
      该 trace 无 span 数据
    </p>
    <template v-else>
      <button
        v-for="row in rows"
        :key="row.span.spanId"
        type="button"
        class="span-row"
        :class="rowClass(row.span)"
        :data-span-id="row.span.spanId"
        :data-depth="row.depth"
        @click="emit('select', row.span)"
      >
        <span
          class="span-name"
          :style="{ paddingLeft: `${row.depth * INDENT_PX}px` }"
        >
          {{ row.span.name }}
        </span>
        <span class="span-track">
          <span
            class="span-bar"
            :style="barStyle(row.span)"
          >
            <span
              v-for="m in markerEvents(row.span)"
              :key="`${row.span.spanId}-${m.ev.name}-${m.ev.time ?? ''}`"
              class="event-marker"
              :class="`event-marker--${m.ev.name.replace(/\./g, '-')}`"
              :data-event-name="m.ev.name"
              :style="{ left: m.left }"
              :title="`${m.ev.name}${m.ev.time ? ` @ ${m.ev.time}` : ''}`"
            />
          </span>
        </span>
        <span
          v-if="isRestSpan(row.span)"
          class="rest-summary"
        >
          {{ restSummary(row.span) }}
        </span>
        <span class="span-duration">{{ row.span.durationMs }}ms</span>
      </button>
    </template>
  </div>
</template>

<style scoped>
.span-timeline {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.span-row {
  display: grid;
  grid-template-columns: minmax(180px, 30%) 1fr minmax(140px, auto) 70px;
  align-items: center;
  gap: 8px;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  background: #fff;
  padding: 4px 8px;
  cursor: pointer;
  text-align: left;
  font: inherit;
}
.span-row:hover {
  background: #f1f5f9;
}
.span-row--biz .span-name {
  font-weight: 600;
}
.span-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.span-track {
  position: relative;
  height: 14px;
  background: #f8fafc;
  border-radius: 2px;
}
.span-bar {
  position: absolute;
  top: 2px;
  bottom: 2px;
  background: #60a5fa;
  border-radius: 2px;
}
.span-row--rest .span-bar {
  background: #34d399;
}
.span-row--error .span-bar {
  background: #f87171;
}
.event-marker {
  position: absolute;
  top: -3px;
  bottom: -3px;
  width: 3px;
  background: #b45309;
}
.event-marker--biz-resume {
  background: #15803d;
}
/* 挂起标记显式声明（评审 n-1：动态修饰类不得只定义一半；与基色一致=琥珀） */
.event-marker--biz-suspend-start {
  background: #b45309;
}
.rest-summary {
  color: #047857;
  font-size: 12px;
  white-space: nowrap;
}
.span-duration {
  color: #64748b;
  font-size: 12px;
  text-align: right;
  /* 非 REST 行缺 rest-summary 子元素，显式钉第 4 列保证耗时列对齐（评审 m-2） */
  grid-column: 4;
}
.empty {
  color: #64748b;
}
</style>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { observeApi, type ObserveApi } from '../api/observeApi'
import type { SpanDetail, SpanNode } from '../api/types'

const props = defineProps<{
  customerId: string
  traceId: string
  span: SpanNode | null
  api?: ObserveApi
}>()
const emit = defineEmits<{ close: [] }>()

const api = computed<ObserveApi>(() => props.api ?? observeApi)

const detail = ref<SpanDetail | null>(null)
const loading = ref(false)
const error = ref('')

// OTLP wire 编号（OtlpJsonParser 原样入库；SDK JsonTraceSerializer 写 kind+1）：
// 0=UNSPECIFIED,1=INTERNAL,2=SERVER,3=CLIENT,4=PRODUCER,5=CONSUMER。
// 用 @opentelemetry/api JS 侧编号会整体错位一档（评审 Major-2/M-1）
const KIND_NAMES: Record<number, string> = {
  0: 'UNSPECIFIED',
  1: 'INTERNAL',
  2: 'SERVER',
  3: 'CLIENT',
  4: 'PRODUCER',
  5: 'CONSUMER'
}

// OTLP Status code（storage 原样存 status.getCode()）：0=UNSET,1=OK,2=ERROR
const STATUS_NAMES: Record<number, string> = {
  0: 'UNSET',
  1: 'OK',
  2: 'ERROR'
}

function kindLabel(kind: number): string {
  return `${KIND_NAMES[kind] ?? 'UNKNOWN'}(${kind})`
}

function statusLabel(code: number): string {
  return `${STATUS_NAMES[code] ?? 'UNKNOWN'}(${code})`
}

// 陈旧响应防护（评审 m-1/F-3）：快速连点不同 span 时，先发的慢响应不得覆盖后选中 span
let requestSeq = 0
watch(
  () => props.span,
  async (span) => {
    const seq = ++requestSeq
    detail.value = null
    error.value = ''
    if (!span) return
    loading.value = true
    try {
      const got = await api.value.getSpan(props.customerId, props.traceId, span.spanId)
      if (seq !== requestSeq) return
      detail.value = got
    } catch (e) {
      if (seq !== requestSeq) return
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      if (seq === requestSeq) loading.value = false
    }
  }
)

// 键盘可达性（评审 Nit）：Esc 关闭抽屉
function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') emit('close')
}
watch(
  () => props.span,
  (span) => {
    if (span) window.addEventListener('keydown', onKeydown)
    else window.removeEventListener('keydown', onKeydown)
  }
)
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <aside
    v-if="span"
    class="span-detail-drawer"
    role="dialog"
    aria-modal="true"
    aria-label="Span 详情"
  >
    <header class="drawer-header">
      <h2>Span 详情</h2>
      <button
        type="button"
        class="drawer-close"
        aria-label="关闭详情"
        @click="$emit('close')"
      >
        ×
      </button>
    </header>
    <p
      v-if="loading"
      class="loading"
    >
      详情加载中…
    </p>
    <p
      v-else-if="error"
      class="error"
      role="alert"
    >
      {{ error }}
    </p>
    <!-- dl 内不允许 section（评审 m-4）：字段区收进 dl，四个区块移出为兄弟，
         attributes 的 dt/dd 各自收进合法 dl -->
    <template v-else-if="detail">
      <dl class="detail-body">
        <div
          class="detail-field"
          data-field="name"
        >
          <dt>name</dt>
          <dd>{{ detail.name }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="traceId"
        >
          <dt>traceId</dt>
          <dd>{{ detail.traceId }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="spanId"
        >
          <dt>spanId</dt>
          <dd>{{ detail.spanId }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="parentSpanId"
        >
          <dt>parentSpanId</dt>
          <dd>{{ detail.parentSpanId || '（root）' }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="kind"
        >
          <dt>kind</dt>
          <dd>{{ kindLabel(detail.kind) }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="statusCode"
        >
          <dt>statusCode</dt>
          <dd>{{ statusLabel(detail.statusCode) }}{{ detail.statusMessage ? `（${detail.statusMessage}）` : '' }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="startTime"
        >
          <dt>startTime</dt>
          <dd>{{ detail.startTime }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="endTime"
        >
          <dt>endTime</dt>
          <dd>{{ detail.endTime }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="durationMs"
        >
          <dt>durationMs</dt>
          <dd>{{ detail.durationMs }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="effectiveDurationMs"
        >
          <dt>effectiveDurationMs</dt>
          <dd>{{ detail.effectiveDurationMs }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="bizCode"
        >
          <dt>bizCode</dt>
          <dd>{{ detail.bizCode ?? '' }}</dd>
        </div>
        <div
          class="detail-field"
          data-field="serviceName"
        >
          <dt>serviceName</dt>
          <dd>{{ detail.serviceName ?? '' }}</dd>
        </div>
      </dl>
      <section class="detail-attributes">
        <h3>attributes</h3>
        <dl>
          <div
            v-for="(value, key) in detail.attributes"
            :key="key"
            class="detail-attr"
            :data-key="key"
          >
            <dt>{{ key }}</dt>
            <dd>{{ value }}</dd>
          </div>
        </dl>
      </section>
      <section class="detail-events">
        <h3>events</h3>
        <div
          v-for="(ev, idx) in detail.events ?? []"
          :key="`${ev.name}-${idx}`"
          class="detail-event"
          :data-event-name="ev.name"
        >
          {{ ev.name }}<template v-if="ev.time">
            @ {{ ev.time }}
          </template>
        </div>
      </section>
      <section class="detail-resource">
        <h3>resource</h3>
        <pre>{{ JSON.stringify(detail.resource, null, 2) }}</pre>
      </section>
      <section class="detail-scope">
        <h3>scope</h3>
        <pre>{{ JSON.stringify(detail.scope, null, 2) }}</pre>
      </section>
    </template>
  </aside>
</template>

<style scoped>
.span-detail-drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(420px, 90vw);
  background: #fff;
  border-left: 1px solid #cbd5e1;
  box-shadow: -4px 0 16px rgb(15 23 42 / 12%);
  padding: 12px 16px;
  overflow-y: auto;
  z-index: 20;
}
.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.drawer-header h2 {
  margin: 0;
  font-size: 16px;
}
.drawer-close {
  border: none;
  background: none;
  font-size: 20px;
  cursor: pointer;
  color: #475569;
}
.detail-body {
  margin: 12px 0;
}
.detail-field,
.detail-attr {
  display: flex;
  gap: 8px;
  border-bottom: 1px solid #f1f5f9;
  padding: 4px 0;
  font-size: 13px;
}
.detail-field dt,
.detail-attr dt {
  flex: 0 0 150px;
  color: #64748b;
  margin: 0;
}
.detail-field dd,
.detail-attr dd {
  margin: 0;
  word-break: break-all;
}
.detail-attributes h3,
.detail-events h3,
.detail-resource h3,
.detail-scope h3 {
  font-size: 13px;
  margin: 12px 0 4px;
  color: #334155;
}
.detail-event {
  font-size: 13px;
  padding: 2px 0;
}
pre {
  font-size: 12px;
  background: #f8fafc;
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
}
.error {
  color: #b91c1c;
}
</style>

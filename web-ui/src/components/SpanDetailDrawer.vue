<script setup lang="ts">
import { ref, watch } from 'vue'
import { observeApi, type ObserveApi } from '../api/observeApi'
import type { SpanDetail, SpanNode } from '../api/types'

const props = defineProps<{
  customerId: string
  traceId: string
  span: SpanNode | null
  api?: ObserveApi
}>()
defineEmits<{ close: [] }>()

const detail = ref<SpanDetail | null>(null)
const loading = ref(false)
const error = ref('')

const KIND_NAMES: Record<number, string> = {
  0: 'INTERNAL',
  1: 'SERVER',
  2: 'CLIENT',
  3: 'PRODUCER',
  4: 'CONSUMER'
}

function kindLabel(kind: number): string {
  return `${KIND_NAMES[kind] ?? 'UNKNOWN'}(${kind})`
}

watch(
  () => props.span,
  async (span) => {
    detail.value = null
    error.value = ''
    if (!span) return
    loading.value = true
    try {
      const api = props.api ?? observeApi
      detail.value = await api.getSpan(props.customerId, props.traceId, span.spanId)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  }
)
</script>

<template>
  <aside
    v-if="span"
    class="span-detail-drawer"
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
    >
      {{ error }}
    </p>
    <dl
      v-else-if="detail"
      class="detail-body"
    >
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
        <dd>{{ detail.statusCode }}{{ detail.statusMessage ? `（${detail.statusMessage}）` : '' }}</dd>
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
      <section class="detail-attributes">
        <h3>attributes</h3>
        <div
          v-for="(value, key) in detail.attributes"
          :key="key"
          class="detail-attr"
          :data-key="key"
        >
          <dt>{{ key }}</dt>
          <dd>{{ value }}</dd>
        </div>
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
    </dl>
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

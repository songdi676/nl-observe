<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { observeApi, type ObserveApi } from '../api/observeApi'
import type { SpanNode, SpanTree } from '../api/types'
import SpanTimeline from '../components/SpanTimeline.vue'
import SpanDetailDrawer from '../components/SpanDetailDrawer.vue'

const props = defineProps<{ api?: ObserveApi }>()
const api = computed<ObserveApi>(() => props.api ?? observeApi)

const route = useRoute()
const traceId = computed(() => String(route.params.traceId ?? ''))
const customerId = computed(() => String(route.query.customer ?? ''))

const tree = ref<SpanTree | null>(null)
const loading = ref(false)
const error = ref('')
const selected = ref<SpanNode | null>(null)

async function load(): Promise<void> {
  selected.value = null
  tree.value = null
  error.value = ''
  if (!traceId.value || !customerId.value) return
  loading.value = true
  try {
    tree.value = await api.value.getTrace(customerId.value, traceId.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

watch([traceId, customerId], load, { immediate: true })
</script>

<template>
  <section class="page trace-page">
    <h1>业务流程时间线</h1>
    <p class="trace-id">
      traceId：{{ traceId || '（待从业务列表选择）' }}
      <template v-if="customerId">
        · 客户：{{ customerId }}
      </template>
    </p>
    <p
      v-if="traceId && !customerId"
      class="hint"
    >
      缺少 customerId：请从业务列表点击 traceId 进入，或在 URL 追加 ?customer=&lt;客户编号&gt;
    </p>
    <p
      v-else-if="!traceId"
      class="hint"
    >
      请从业务列表选择一条业务查看流程时间线
    </p>
    <p
      v-else-if="loading"
      class="loading"
    >
      时间线加载中…
    </p>
    <p
      v-else-if="error"
      class="error"
      role="alert"
    >
      加载失败：{{ error }}
    </p>
    <SpanTimeline
      v-else-if="tree"
      :spans="tree.spans"
      @select="selected = $event"
    />
    <SpanDetailDrawer
      :customer-id="customerId"
      :trace-id="traceId"
      :span="selected"
      :api="api"
      @close="selected = null"
    />
  </section>
</template>

<style scoped>
.trace-id {
  color: #475569;
  font-size: 13px;
  word-break: break-all;
}
.hint,
.loading {
  color: #64748b;
}
.error {
  color: #b91c1c;
}
</style>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { observeApi, type ObserveApi } from '../api/observeApi'
import type { BusinessItem } from '../api/types'

const props = defineProps<{ api?: ObserveApi }>()
const api = computed<ObserveApi>(() => props.api ?? observeApi)

const customerId = ref('')
const from = ref('')
const to = ref('')
const bizCode = ref('')

const items = ref<BusinessItem[]>([])
const loading = ref(false)
const error = ref('')
const searched = ref(false)
/** 查询时的 customerId 快照（评审 Nit）：结果行的链接不得随输入框后续改动漂移 */
const lastCustomer = ref('')

/** from/to/customerId 必填（详设 §2.1：缺参后端 40001），空则禁用以避免无效请求；
 *  loading 期间禁用防重复提交（评审 m-1：双击后到者覆盖） */
const canQuery = computed(
  () => customerId.value !== '' && from.value !== '' && to.value !== ''
)

async function query(): Promise<void> {
  if (!canQuery.value || loading.value) return
  loading.value = true
  error.value = ''
  lastCustomer.value = customerId.value
  try {
    items.value = await api.value.listBusinesses(customerId.value, {
      from: from.value,
      to: to.value,
      bizCode: bizCode.value
    })
  } catch (e) {
    items.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
    searched.value = true
  }
}

function traceLink(item: BusinessItem): { path: string; query: Record<string, string> } {
  // traceId 进 path 前编码（评审安全 F-2）：含 ?/#// 的畸形 traceId 不得破坏 href 结构；
  // vue-router 取参自动解码，接收端再经 API 层 encodeURIComponent，口径与 observeApi 一致
  return {
    path: `/traces/${encodeURIComponent(item.traceId)}`,
    query: { customer: lastCustomer.value }
  }
}
</script>

<template>
  <section class="page businesses-page">
    <h1>业务列表</h1>
    <form
      class="filters"
      @submit.prevent="query"
    >
      <label>
        客户编号
        <input
          v-model="customerId"
          name="customerId"
          type="text"
        >
      </label>
      <label>
        开始日期
        <input
          v-model="from"
          name="from"
          type="date"
        >
      </label>
      <label>
        结束日期
        <input
          v-model="to"
          name="to"
          type="date"
        >
      </label>
      <label>
        业务码
        <input
          v-model="bizCode"
          name="bizCode"
          type="text"
        >
      </label>
      <button
        type="submit"
        class="query-btn"
        :disabled="!canQuery || loading"
      >
        查询
      </button>
    </form>

    <p
      v-if="loading"
      class="loading"
    >
      查询中…
    </p>
    <p
      v-else-if="error"
      class="error"
      role="alert"
    >
      查询失败：{{ error }}
    </p>
    <template v-else>
      <table
        v-if="items.length > 0"
        class="business-table"
      >
        <thead>
          <tr>
            <th>bizId</th>
            <th>bizCode</th>
            <th>traceId</th>
            <th>startTime</th>
            <th>effectiveDurationMs</th>
            <th>status</th>
            <th>endReason</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in items"
            :key="item.bizId"
            class="business-row"
            :data-biz-id="item.bizId"
          >
            <td class="cell-bizId">
              {{ item.bizId }}
            </td>
            <td class="cell-bizCode">
              {{ item.bizCode }}
            </td>
            <td class="cell-traceId">
              <RouterLink
                class="trace-link"
                :to="traceLink(item)"
              >
                {{ item.traceId }}
              </RouterLink>
            </td>
            <td class="cell-startTime">
              {{ item.startTime }}
            </td>
            <td class="cell-effectiveDurationMs">
              {{ item.effectiveDurationMs }}ms
            </td>
            <td
              class="cell-status"
              :class="{ 'status-fail': item.status !== 'success' }"
            >
              {{ item.status }}
            </td>
            <td class="cell-endReason">
              {{ item.endReason }}
            </td>
          </tr>
        </tbody>
      </table>
      <p
        v-else-if="searched"
        class="empty"
      >
        该客户在所选时间范围内无业务记录
      </p>
    </template>
  </section>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}
.filters label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: #475569;
}
.query-btn {
  border: 1px solid #2563eb;
  background: #2563eb;
  color: #fff;
  border-radius: 4px;
  padding: 6px 16px;
  cursor: pointer;
}
.query-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.business-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.business-table th,
.business-table td {
  border: 1px solid #e2e8f0;
  padding: 6px 8px;
  text-align: left;
}
.business-table th {
  background: #f8fafc;
}
.business-row:hover {
  background: #f1f5f9;
  cursor: pointer;
}
.trace-link {
  color: #2563eb;
  text-decoration: none;
  word-break: break-all;
}
.status-fail {
  color: #b91c1c;
  font-weight: 600;
}
.error {
  color: #b91c1c;
}
.empty,
.loading {
  color: #64748b;
}
</style>

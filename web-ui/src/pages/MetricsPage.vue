<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError, observeApi, type ObserveApi } from '../api/observeApi'
import type { MetricSummary, MetricsSummaryQuery } from '../api/types'

const props = defineProps<{ api?: ObserveApi }>()
const api = computed<ObserveApi>(() => props.api ?? observeApi)

const customerId = ref('')
const from = ref('')
const to = ref('')
const bizCode = ref('')

const summary = ref<MetricSummary | null>(null)
/** 后端 40401 = 所选范围完全无数据（详设 §2.4/§4.1）→ 展示空数据占位态，而非错误态 */
const empty = ref(false)
const loading = ref(false)
/** 错误态错误码（评审 sec-L-1：页面只展示错误码供排查，不逐字展示后端 message——
 *  50000 类故障的 message 可能含内部细节；原始错误写 console 供排障） */
const errorCode = ref<number | null>(null)
/** 查询参数快照：重试按快照重发，不受输入框后续改动影响（与 BusinessesPage lastCustomer 同口径） */
const lastQuery = ref<MetricsSummaryQuery | null>(null)

/** from/to/customerId 必填（恒带 customerId，规避运维视图限流桶；必填性已由
 *  MetricsSummaryQuery 类型钉进编译器，评审 arch M-1） */
const canQuery = computed(
  () => customerId.value !== '' && from.value !== '' && to.value !== ''
)

// 陈旧响应防护（评审 arch M-3）：当前 UI 无路径触发并发请求（提交按钮 loading
// 期间禁用、retry 同样受 loading gating），loadSeq 是针对未来交互变更（如自动
// 刷新/解除并发锁）的防御代码——届时生效，保证旧响应不覆盖新查询状态
let loadSeq = 0
async function doQuery(q: MetricsSummaryQuery): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  errorCode.value = null
  empty.value = false
  summary.value = null
  lastQuery.value = q
  try {
    const got = await api.value.getMetricsSummary(q)
    if (seq !== loadSeq) return
    if (got == null) {
      // 后端契约违约兜底（评审 N-1）：code 0 + data:null → 错误态，不静默空白
      throw new ApiError(-1, 'metrics/summary returned empty data (contract violation)')
    }
    summary.value = got
  } catch (e) {
    if (seq !== loadSeq) return
    if (e instanceof ApiError && e.code === 40401) {
      empty.value = true
    } else {
      console.error('[web-ui] metrics/summary query failed:', e)
      errorCode.value = e instanceof ApiError ? e.code : -1
    }
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function query(): void {
  if (!canQuery.value || loading.value) return
  void doQuery({
    customerId: customerId.value,
    from: from.value,
    to: to.value,
    bizCode: bizCode.value
  })
}

function retry(): void {
  if (loading.value || lastQuery.value === null) return
  void doQuery(lastQuery.value)
}

/** 毫秒格式化：整数不带小数位（52100 ms），小数保留一位（1830.5 ms） */
function formatMs(v: number): string {
  return `${Number.isInteger(v) ? String(v) : v.toFixed(1)} ms`
}

/** 成功率 0~1 → 百分比（保留一位小数） */
function formatRate(v: number): string {
  return `${(v * 100).toFixed(1)}%`
}
</script>

<template>
  <section class="page metrics-page">
    <h1>性能指标</h1>
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
      <!-- loading 期间禁用防重复提交（该行为属按钮而非 canQuery，评审 N-3） -->
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
      指标加载中…
    </p>
    <template v-else-if="errorCode !== null">
      <p
        class="error"
        role="alert"
      >
        指标加载失败（错误码 {{ errorCode }}）
      </p>
      <button
        type="button"
        class="retry-btn"
        @click="retry"
      >
        重试
      </button>
    </template>
    <template v-else-if="summary">
      <div class="metric-cards">
        <article
          class="metric-card first-screen-card"
          data-metric="firstScreenLoadP95"
        >
          <h2>
            首屏加载耗时 P95
          </h2>
          <p
            v-if="summary.firstScreenLoadP95 === null"
            class="empty"
          >
            暂无数据
          </p>
          <p
            v-else
            class="metric-value"
          >
            {{ formatMs(summary.firstScreenLoadP95) }}
          </p>
        </article>
      </div>
      <h2>
        业务指标
      </h2>
      <div
        v-if="summary.perBiz.length > 0"
        class="metric-cards"
      >
        <article
          v-for="biz in summary.perBiz"
          :key="biz.bizCode"
          class="metric-card biz-card"
          :data-biz-code="biz.bizCode"
        >
          <h3>
            {{ biz.bizCode }}
          </h3>
          <dl class="biz-metric-list">
            <div class="biz-metric">
              <dt>
                业务耗时 P95
              </dt>
              <dd class="metric-value">
                {{ formatMs(biz.effectiveDurationP95) }}
              </dd>
            </div>
            <div class="biz-metric">
              <dt>
                成功率
              </dt>
              <dd class="metric-value">
                {{ formatRate(biz.successRate) }}
              </dd>
            </div>
            <div class="biz-metric">
              <dt>
                业务数
              </dt>
              <dd class="metric-value">
                {{ biz.count }}
              </dd>
            </div>
          </dl>
        </article>
      </div>
      <p
        v-else
        class="empty"
      >
        暂无业务指标数据
      </p>
    </template>
    <!-- empty 只在 doQuery 完成后（同 seq finally）才可能为 true，已隐含“已查询”语义（评审 arch M-4） -->
    <p
      v-else-if="empty"
      class="empty"
    >
      所选时间范围内无指标数据
    </p>
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
.retry-btn {
  border: 1px solid #2563eb;
  background: #fff;
  color: #2563eb;
  border-radius: 4px;
  padding: 4px 12px;
  cursor: pointer;
}
.metric-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 12px 0;
}
.metric-card {
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #f8fafc;
  padding: 12px 16px;
  min-width: 220px;
}
.metric-card h2,
.metric-card h3 {
  margin: 0 0 8px;
  font-size: 14px;
  color: #334155;
}
.metric-value {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: #0f172a;
}
.biz-metric-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.biz-metric {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}
.biz-metric dt {
  font-size: 13px;
  color: #64748b;
}
.biz-metric dd {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}
.error {
  color: #b91c1c;
}
.empty,
.loading {
  color: #64748b;
}
</style>

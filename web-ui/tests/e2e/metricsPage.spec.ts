/**
 * E2E（jsdom 全应用级）：MetricsPage 加载 → 指标卡片存在性（不断言精确值）。
 * 与 tracePage.spec.ts 同口径：走真实路由 + 真实默认 observeApi 单例
 * （baseURL 取 VITE_API_BASE_URL 缺省 /api/observe），网络层由 msw 以 jsdom origin 拦截；
 * 真实后端全链路 E2E 见仓库根 tests/e2e（Playwright，T23，env-gated——非本目录，评审 N-2）。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '../../src/router'
import App from '../../src/App.vue'

const ORIGIN = 'http://localhost:3000'
const BASE = `${ORIGIN}/api/observe`

const summaryFixture = {
  firstScreenLoadP95: 1830.5,
  perBiz: [{ bizCode: 'ORDER', effectiveDurationP95: 52100, successRate: 0.982, count: 552 }]
}

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('E2E：MetricsPage 加载 → 指标卡片存在性', () => {
  it('填写客户编号与时间范围查询后，首屏与业务指标卡片存在', async () => {
    server.use(
      http.get(`${BASE}/metrics/summary`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: summaryFixture })
      )
    )
    const router = createAppRouter(createMemoryHistory())
    const wrapper = mount(App, { global: { plugins: [router] } })
    await router.push('/metrics')
    await flushPromises()

    const page = wrapper.get('.metrics-page')
    await page.find('input[name="customerId"]').setValue('c-001')
    await page.find('input[name="from"]').setValue('2026-09-01')
    await page.find('input[name="to"]').setValue('2026-09-28')
    await page.find('form').trigger('submit')
    await flushPromises()
    await new Promise((r) => setTimeout(r, 0))
    await flushPromises()

    // 只断言卡片存在性，不断言精确值
    expect(wrapper.find('.metric-card[data-metric="firstScreenLoadP95"]').exists()).toBe(true)
    expect(wrapper.find('.metric-card[data-biz-code="ORDER"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

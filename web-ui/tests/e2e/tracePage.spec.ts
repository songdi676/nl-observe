/**
 * E2E（jsdom 全应用级）：TracePage 加载样例 trace → 时间线包含业务 span 与 REST span。
 * 走真实路由 + 真实默认 observeApi 单例（baseURL 取 VITE_API_BASE_URL 缺省 /api/observe），
 * 网络层由 msw 以 jsdom origin 拦截；真实后端全链路 E2E 见 tests/e2e（Playwright，T23，env-gated）。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '../../src/router'
import App from '../../src/App.vue'
import type { SpanTree } from '../../src/api/types'

const ORIGIN = 'http://localhost:3000'
const BASE = `${ORIGIN}/api/observe`

const sampleTrace: SpanTree = {
  traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
  spans: [
    {
      spanId: 's1',
      parentSpanId: '',
      name: 'biz.business.ORDER',
      kind: 1,
      startTime: '2026-09-28T09:00:00.000Z',
      endTime: '2026-09-28T09:00:45.210Z',
      statusCode: 0,
      durationMs: 45210,
      effectiveDurationMs: 45210,
      attributes: { 'biz.status': 'success' },
      events: [
        { name: 'biz.suspend_start', time: '2026-09-28T09:00:10.000Z' },
        { name: 'biz.resume', time: '2026-09-28T09:00:20.000Z' }
      ]
    },
    {
      spanId: 's2',
      parentSpanId: 's1',
      name: 'GET /api/orders',
      kind: 3,
      startTime: '2026-09-28T09:00:05.000Z',
      endTime: '2026-09-28T09:00:06.000Z',
      statusCode: 0,
      durationMs: 1000,
      attributes: {
        'http.request.method': 'GET',
        'http.route': '/api/orders',
        'http.response.status_code': 200,
        'nl.duration_ms': 1000
      }
    }
  ]
}

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('E2E：TracePage 加载样例 trace', () => {
  it('时间线包含业务 span 与 REST span（含挂起/恢复标记）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/traces/:traceId`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: sampleTrace })
      )
    )
    const router = createAppRouter(createMemoryHistory())
    const wrapper = mount(App, { global: { plugins: [router] } })
    await router.push('/traces/4bf92f3577b34da6a3ce929d0e0e4736?customer=c-001')
    await flushPromises()
    await new Promise((r) => setTimeout(r, 0))
    await flushPromises()

    const rows = wrapper.findAll('.span-row')
    expect(rows.map((r) => r.attributes('data-span-id'))).toEqual(['s1', 's2'])
    expect(rows[0].classes()).toContain('span-row--biz')
    expect(rows[1].classes()).toContain('span-row--rest')
    const markers = wrapper
      .get('.span-row[data-span-id="s1"]')
      .findAll('.event-marker')
      .map((m) => m.attributes('data-event-name'))
    expect(markers).toContain('biz.suspend_start')
    expect(markers).toContain('biz.resume')
    wrapper.unmount()
  })
})

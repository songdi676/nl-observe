import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '../src/router'
import TracePage from '../src/pages/TracePage.vue'
import { createObserveApi } from '../src/api/observeApi'
import type { SpanDetail, SpanTree } from '../src/api/types'

const BASE = 'http://api.test/api/observe'

const bizRoot = {
  spanId: 's1',
  parentSpanId: '',
  name: 'biz.business.ORDER',
  kind: 1,
  startTime: '2026-09-28T09:00:00.000Z',
  endTime: '2026-09-28T09:00:45.210Z',
  statusCode: 0,
  durationMs: 45210,
  effectiveDurationMs: 45210,
  attributes: {
    'biz.status': 'success',
    'biz.total_suspended_ms': 10000,
    'biz.end_reason': 'explicit'
  },
  events: [
    { name: 'biz.suspend_start', time: '2026-09-28T09:00:10.000Z' },
    { name: 'biz.resume', time: '2026-09-28T09:00:20.000Z' }
  ]
}

const restGet = {
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

const restPost = {
  spanId: 's3',
  parentSpanId: 's1',
  name: 'POST /api/orders',
  kind: 3,
  startTime: '2026-09-28T09:00:30.000Z',
  endTime: '2026-09-28T09:00:31.000Z',
  statusCode: 1,
  durationMs: 1000,
  attributes: {
    'http.request.method': 'POST',
    'http.route': '/api/orders',
    'http.response.status_code': 500,
    'nl.duration_ms': 1000
  }
}

const traceFixture: SpanTree = { traceId: 't-1', spans: [bizRoot, restGet, restPost] }

const spanDetailFixture: SpanDetail = {
  ...restGet,
  traceId: 't-1',
  traceState: '',
  statusMessage: null,
  bizCode: 'ORDER',
  serviceName: 'web-app',
  resource: { 'service.name': 'web-app' },
  scope: { name: '@nl-observe/sdk-web' }
}

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

async function mountTrace(path = '/traces/t-1?customer=c-001'): Promise<VueWrapper> {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  return mount(TracePage, {
    props: { api: createObserveApi({ baseURL: BASE }) },
    global: { plugins: [router] }
  })
}

describe('TracePage：span 树渲染为时间线/瀑布', () => {
  it('业务 root span 与 REST span 层级正确（DFS 顺序 + 深度缩进）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/traces/:traceId`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: traceFixture })
      )
    )
    const wrapper = await mountTrace()
    await flushPromises()
    const rows = wrapper.findAll('.span-row')
    expect(rows.map((r) => [r.attributes('data-span-id'), r.attributes('data-depth')])).toEqual([
      ['s1', '0'],
      ['s2', '1'],
      ['s3', '1']
    ])
    expect(rows[0].classes()).toContain('span-row--biz')
    expect(rows[1].classes()).toContain('span-row--rest')
    expect(rows[2].classes()).toContain('span-row--rest')
  })

  it('REST span 展开白名单属性（method/route/status 摘要可见）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/traces/:traceId`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: traceFixture })
      )
    )
    const wrapper = await mountTrace()
    await flushPromises()
    const summary = wrapper.get('.span-row[data-span-id="s2"] .rest-summary').text()
    expect(summary).toContain('GET')
    expect(summary).toContain('/api/orders')
    expect(summary).toContain('200')
  })

  it('挂起/恢复 span events 在时间线可见（biz.suspend_start/biz.resume 标记）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/traces/:traceId`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: traceFixture })
      )
    )
    const wrapper = await mountTrace()
    await flushPromises()
    const row = wrapper.get('.span-row[data-span-id="s1"]')
    const markers = row.findAll('.event-marker').map((m) => m.attributes('data-event-name'))
    expect(markers).toEqual(['biz.suspend_start', 'biz.resume'])
  })

  it('点击 span 打开详情抽屉（GET /spans/{spanId} 全字段 + 白名单属性）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/traces/:traceId`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: traceFixture })
      ),
      http.get(`${BASE}/customers/:customerId/traces/:traceId/spans/:spanId`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: spanDetailFixture })
      )
    )
    const wrapper = await mountTrace()
    await flushPromises()
    expect(wrapper.find('.span-detail-drawer').exists()).toBe(false)
    await wrapper.get('.span-row[data-span-id="s2"]').trigger('click')
    await flushPromises()
    const drawer = wrapper.get('.span-detail-drawer')
    expect(drawer.find('.detail-field[data-field="spanId"]').text()).toContain('s2')
    expect(drawer.find('.detail-field[data-field="traceId"]').text()).toContain('t-1')
    expect(drawer.find('.detail-attr[data-key="http.route"]').text()).toContain('/api/orders')
    expect(drawer.find('.detail-attr[data-key="http.response.status_code"]').text()).toContain('200')
    expect(drawer.find('.detail-resource').text()).toContain('web-app')
    expect(drawer.find('.detail-scope').text()).toContain('@nl-observe/sdk-web')
  })

  it('抽屉关闭按钮收起详情', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/traces/:traceId`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: traceFixture })
      ),
      http.get(`${BASE}/customers/:customerId/traces/:traceId/spans/:spanId`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: spanDetailFixture })
      )
    )
    const wrapper = await mountTrace()
    await flushPromises()
    await wrapper.get('.span-row[data-span-id="s2"]').trigger('click')
    await flushPromises()
    await wrapper.get('.drawer-close').trigger('click')
    expect(wrapper.find('.span-detail-drawer').exists()).toBe(false)
  })

  it('trace 不存在（40401）→ 错误态', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/traces/:traceId`, () =>
        HttpResponse.json({ code: 40401, message: 'trace not found', data: null }, { status: 404 })
      )
    )
    const wrapper = await mountTrace()
    await flushPromises()
    expect(wrapper.find('.error').text()).toContain('trace not found')
  })

  it('缺 customerId → 提示态，不发请求', async () => {
    const wrapper = await mountTrace('/traces/t-1')
    await flushPromises()
    expect(wrapper.find('.hint').exists()).toBe(true)
    expect(wrapper.text()).toContain('t-1')
  })
})

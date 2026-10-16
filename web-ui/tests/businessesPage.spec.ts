import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '../src/router'
import BusinessesPage from '../src/pages/BusinessesPage.vue'
import { createObserveApi } from '../src/api/observeApi'

const BASE = 'http://api.test/api/observe'

const businessFixture = [
  {
    bizId: 'b-001',
    bizCode: 'ORDER',
    traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    startTime: '2026-09-28T09:00:00.000Z',
    effectiveDurationMs: 45210,
    status: 'success',
    endReason: 'explicit'
  },
  {
    bizId: 'b-002',
    bizCode: 'PAYMENT',
    traceId: 'aaa92f3577b34da6a3ce929d0e0e4737',
    startTime: '2026-09-28T10:30:00.000Z',
    effectiveDurationMs: 12000,
    status: 'fail',
    endReason: 'unmount'
  }
]

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

async function mountPage() {
  const router = createAppRouter(createMemoryHistory())
  await router.push('/businesses')
  const wrapper = mount(BusinessesPage, {
    props: { api: createObserveApi({ baseURL: BASE }) },
    global: { plugins: [router] }
  })
  return { wrapper, router }
}

async function fillAndSubmit(wrapper: Awaited<ReturnType<typeof mountPage>>['wrapper']) {
  await wrapper.find('input[name="customerId"]').setValue('c-001')
  await wrapper.find('input[name="from"]').setValue('2026-09-01')
  await wrapper.find('input[name="to"]').setValue('2026-09-28')
  await wrapper.find('input[name="bizCode"]').setValue('ORDER')
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('BusinessesPage：客户编号+时间范围+bizCode 过滤查询业务列表', () => {
  it('查询后渲染列表，字段与 API 一致', async () => {
    let capturedUrl = ''
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ code: 0, message: 'ok', data: businessFixture })
      })
    )
    const { wrapper } = await mountPage()
    await fillAndSubmit(wrapper)

    const rows = wrapper.findAll('tr[data-biz-id]')
    expect(rows.map((r) => r.attributes('data-biz-id'))).toEqual(['b-001', 'b-002'])

    const row = rows[0]
    expect(row.find('.cell-bizCode').text()).toBe('ORDER')
    expect(row.find('.cell-traceId').text()).toContain('4bf92f3577b34da6a3ce929d0e0e4736')
    expect(row.find('.cell-startTime').text()).toBe('2026-09-28T09:00:00.000Z')
    expect(row.find('.cell-effectiveDurationMs').text()).toContain('45210')
    expect(row.find('.cell-status').text()).toBe('success')
    expect(row.find('.cell-endReason').text()).toBe('explicit')

    const url = new URL(capturedUrl)
    expect(url.pathname).toBe('/api/observe/customers/c-001/businesses')
    expect(url.searchParams.get('from')).toBe('2026-09-01')
    expect(url.searchParams.get('to')).toBe('2026-09-28')
    expect(url.searchParams.get('bizCode')).toBe('ORDER')
  })

  it('traceId 列链接到 TracePage 并携带 customerId', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: businessFixture })
      )
    )
    const { wrapper } = await mountPage()
    await fillAndSubmit(wrapper)
    const link = wrapper.get('tr[data-biz-id="b-001"] a')
    expect(link.attributes('href')).toBe(
      '/traces/4bf92f3577b34da6a3ce929d0e0e4736?customer=c-001'
    )
  })

  it('空数据 → 占位态', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: [] })
      )
    )
    const { wrapper } = await mountPage()
    await fillAndSubmit(wrapper)
    expect(wrapper.findAll('tr[data-biz-id]')).toHaveLength(0)
    expect(wrapper.find('.empty').exists()).toBe(true)
  })

  it('参数错误（40001）→ 错误态展示后端 message', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, () =>
        HttpResponse.json({ code: 40001, message: 'from/to 必填', data: null }, { status: 400 })
      )
    )
    const { wrapper } = await mountPage()
    await fillAndSubmit(wrapper)
    expect(wrapper.find('.error').text()).toContain('from/to 必填')
  })

  it('必填项缺失时查询按钮禁用（不发请求）', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('畸形 traceId（含 ? 与 /）：链接 path 编码，不破坏 href 结构（评审安全 F-2）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, () =>
        HttpResponse.json({
          code: 0,
          message: 'ok',
          data: [
            {
              bizId: 'b-900',
              bizCode: 'ORDER',
              traceId: 'aaa?bbb/ccc',
              startTime: '2026-09-28T09:00:00.000Z',
              effectiveDurationMs: 1000,
              status: 'success',
              endReason: 'explicit'
            }
          ]
        })
      )
    )
    const { wrapper } = await mountPage()
    await fillAndSubmit(wrapper)
    const link = wrapper.get('tr[data-biz-id="b-900"] a')
    // path 段整体 percent-encode，?customer= 保持唯一 query
    expect(link.attributes('href')).toBe('/traces/aaa%3Fbbb%2Fccc?customer=c-001')
  })

  it('查询期间输入框改动 customerId：结果行链接 customer 不漂移（评审 Nit）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: businessFixture })
      )
    )
    const { wrapper } = await mountPage()
    await fillAndSubmit(wrapper)
    // 查询后改动输入框（不重新查询）
    await wrapper.find('input[name="customerId"]').setValue('c-999')
    const link = wrapper.get('tr[data-biz-id="b-001"] a')
    expect(link.attributes('href')).toBe(
      '/traces/4bf92f3577b34da6a3ce929d0e0e4736?customer=c-001'
    )
  })
})

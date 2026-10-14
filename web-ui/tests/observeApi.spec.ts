import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { ApiError, createObserveApi } from '../src/api/observeApi'
import type { SpanTree } from '../src/api/types'

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
  }
]

const spanTreeFixture: SpanTree = {
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
      events: [{ name: 'biz.suspend_start' }]
    }
  ]
}

const metricsFixture = {
  firstScreenLoadP95: 1830.5,
  perBiz: [
    {
      bizCode: 'ORDER',
      effectiveDurationP95: 52100.0,
      successRate: 0.982,
      count: 552
    }
  ]
}

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('observeApi 统一解包 {code,message,data}', () => {
  it('code=0 时 resolve data（业务列表）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: businessFixture })
      )
    )
    const api = createObserveApi({ baseURL: BASE })
    const result = await api.listBusinesses('c-001', {
      from: '2026-09-01',
      to: '2026-09-28'
    })
    expect(result).toEqual(businessFixture)
  })

  it('code=0 时 resolve data（trace/span/metrics）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/traces/:traceId`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: spanTreeFixture })
      ),
      http.get(`${BASE}/customers/:customerId/traces/:traceId/spans/:spanId`, () =>
        HttpResponse.json({
          code: 0,
          message: 'ok',
          data: { ...spanTreeFixture.spans[0], traceId: spanTreeFixture.traceId }
        })
      ),
      http.get(`${BASE}/metrics/summary`, () =>
        HttpResponse.json({ code: 0, message: 'ok', data: metricsFixture })
      )
    )
    const api = createObserveApi({ baseURL: BASE })
    await expect(api.getTrace('c-001', 't-1')).resolves.toEqual(spanTreeFixture)
    await expect(api.getSpan('c-001', 't-1', 's1')).resolves.toMatchObject({
      spanId: 's1'
    })
    await expect(
      api.getMetricsSummary({ from: '2026-09-01', to: '2026-09-28', customerId: 'c-001' })
    ).resolves.toEqual(metricsFixture)
  })

  it('请求路径与查询参数按详细设计 §2 拼装', async () => {
    let capturedUrl = ''
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ code: 0, message: 'ok', data: [] })
      })
    )
    const api = createObserveApi({ baseURL: BASE })
    await api.listBusinesses('c-001', {
      from: '2026-09-01',
      to: '2026-09-28',
      bizCode: 'ORDER'
    })
    const url = new URL(capturedUrl)
    expect(url.pathname).toBe('/api/observe/customers/c-001/businesses')
    expect(url.searchParams.get('from')).toBe('2026-09-01')
    expect(url.searchParams.get('to')).toBe('2026-09-28')
    expect(url.searchParams.get('bizCode')).toBe('ORDER')
  })

  it('可选参数缺省时不出现在查询串', async () => {
    let capturedUrl = ''
    server.use(
      http.get(`${BASE}/metrics/summary`, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ code: 0, message: 'ok', data: metricsFixture })
      })
    )
    const api = createObserveApi({ baseURL: BASE })
    await api.getMetricsSummary({ from: '2026-09-01', to: '2026-09-28' })
    const url = new URL(capturedUrl)
    expect(url.searchParams.has('bizCode')).toBe(false)
    expect(url.searchParams.has('customerId')).toBe(false)
  })
})

describe('observeApi 非 0 code 抛业务错误', () => {
  it('code=40001 抛 ApiError（参数错误）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, () =>
        HttpResponse.json({ code: 40001, message: 'from/to 必填', data: null })
      )
    )
    const api = createObserveApi({ baseURL: BASE })
    const err = await api
      .listBusinesses('c-001', { from: '', to: '' })
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe(40001)
    expect((err as ApiError).message).toBe('from/to 必填')
  })

  it('code=40401 抛 ApiError（无数据）', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/traces/:traceId`, () =>
        HttpResponse.json({ code: 40401, message: 'trace not found', data: null })
      )
    )
    const api = createObserveApi({ baseURL: BASE })
    await expect(api.getTrace('c-001', 'missing')).rejects.toMatchObject({
      code: 40401
    })
  })
})

describe('observeApi 401 跳转登录', () => {
  it('HTTP 401 时调用 onUnauthorized(loginUrl) 并抛错', async () => {
    server.use(
      http.get(`${BASE}/customers/:customerId/businesses`, () =>
        HttpResponse.json({ message: 'unauthorized' }, { status: 401 })
      )
    )
    const onUnauthorized = vi.fn()
    const api = createObserveApi({
      baseURL: BASE,
      loginUrl: 'https://gateway.test/login',
      onUnauthorized
    })
    await expect(
      api.listBusinesses('c-001', { from: '2026-09-01', to: '2026-09-28' })
    ).rejects.toThrow()
    expect(onUnauthorized).toHaveBeenCalledWith('https://gateway.test/login')
  })

  it('非 401 的 HTTP 错误不触发跳转', async () => {
    server.use(
      http.get(`${BASE}/metrics/summary`, () =>
        HttpResponse.json({ code: 50000, message: 'internal', data: null }, { status: 500 })
      )
    )
    const onUnauthorized = vi.fn()
    const api = createObserveApi({ baseURL: BASE, onUnauthorized })
    await expect(
      api.getMetricsSummary({ from: '2026-09-01', to: '2026-09-28' })
    ).rejects.toThrow()
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
})

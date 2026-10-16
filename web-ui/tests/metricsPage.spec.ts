import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import MetricsPage from '../src/pages/MetricsPage.vue'
import { createObserveApi } from '../src/api/observeApi'

const BASE = 'http://api.test/api/observe'

/** 与详设 §2.4 响应示例对齐 */
const summaryFixture = {
  firstScreenLoadP95: 1830.5,
  perBiz: [
    { bizCode: 'ORDER', effectiveDurationP95: 52100, successRate: 0.982, count: 552 },
    { bizCode: 'PAYMENT', effectiveDurationP95: 9000, successRate: 1, count: 87 }
  ]
}

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

function mountPage() {
  return mount(MetricsPage, {
    props: { api: createObserveApi({ baseURL: BASE }) }
  })
}

async function fillAndSubmit(wrapper: ReturnType<typeof mountPage>, withBizCode = true) {
  await wrapper.find('input[name="customerId"]').setValue('c-001')
  await wrapper.find('input[name="from"]').setValue('2026-09-01')
  await wrapper.find('input[name="to"]').setValue('2026-09-28')
  if (withBizCode) {
    await wrapper.find('input[name="bizCode"]').setValue('ORDER')
  }
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('MetricsPage：调用 /metrics/summary（恒带 customerId）展示指标卡片', () => {
  it('mock 数据渲染：firstScreenLoadP95 与 perBiz 卡片值与 API 一致', async () => {
    let capturedUrl = ''
    server.use(
      http.get(`${BASE}/metrics/summary`, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ code: 0, message: 'ok', data: summaryFixture })
      })
    )
    const wrapper = mountPage()
    await fillAndSubmit(wrapper)

    const url = new URL(capturedUrl)
    expect(url.pathname).toBe('/api/observe/metrics/summary')
    expect(url.searchParams.get('customerId')).toBe('c-001')
    expect(url.searchParams.get('from')).toBe('2026-09-01')
    expect(url.searchParams.get('to')).toBe('2026-09-28')
    expect(url.searchParams.get('bizCode')).toBe('ORDER')

    const firstCard = wrapper.get('.metric-card[data-metric="firstScreenLoadP95"]')
    expect(firstCard.find('.metric-value').text()).toContain('1830.5')

    const orderCard = wrapper.get('.metric-card[data-biz-code="ORDER"]')
    expect(orderCard.text()).toContain('ORDER')
    expect(orderCard.find('.metric-value').exists()).toBe(true)
    const orderValues = orderCard.findAll('.metric-value').map((n) => n.text())
    expect(orderValues.join(' ')).toContain('52100')
    expect(orderValues.join(' ')).toContain('98.2%')
    expect(orderValues.join(' ')).toContain('552')

    const payCard = wrapper.get('.metric-card[data-biz-code="PAYMENT"]')
    const payValues = payCard.findAll('.metric-value').map((n) => n.text())
    expect(payValues.join(' ')).toContain('9000')
    expect(payValues.join(' ')).toContain('100.0%')
    expect(payValues.join(' ')).toContain('87')
  })

  it('bizCode 为空时不进查询串（后端可选参数）', async () => {
    let capturedUrl = ''
    server.use(
      http.get(`${BASE}/metrics/summary`, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ code: 0, message: 'ok', data: summaryFixture })
      })
    )
    const wrapper = mountPage()
    await fillAndSubmit(wrapper, false)
    const url = new URL(capturedUrl)
    expect(url.searchParams.has('bizCode')).toBe(false)
  })

  it('首屏指标为 null 且存在业务指标：首屏卡占位“暂无数据”，业务卡正常渲染', async () => {
    server.use(
      http.get(`${BASE}/metrics/summary`, () =>
        HttpResponse.json({
          code: 0,
          message: 'ok',
          data: { firstScreenLoadP95: null, perBiz: [summaryFixture.perBiz[0]] }
        })
      )
    )
    const wrapper = mountPage()
    await fillAndSubmit(wrapper)

    const firstCard = wrapper.get('.metric-card[data-metric="firstScreenLoadP95"]')
    expect(firstCard.find('.empty').exists()).toBe(true)
    expect(firstCard.find('.empty').text()).toContain('暂无数据')
    expect(wrapper.find('.metric-card[data-biz-code="ORDER"]').exists()).toBe(true)
  })

  it('业务指标为空数组：业务区占位态', async () => {
    server.use(
      http.get(`${BASE}/metrics/summary`, () =>
        HttpResponse.json({
          code: 0,
          message: 'ok',
          data: { firstScreenLoadP95: 1830.5, perBiz: [] }
        })
      )
    )
    const wrapper = mountPage()
    await fillAndSubmit(wrapper)

    expect(wrapper.findAll('.metric-card[data-biz-code]')).toHaveLength(0)
    expect(wrapper.text()).toContain('暂无业务指标数据')
  })

  it('完全无数据（40401）→ 占位态，不进入错误态', async () => {
    server.use(
      http.get(`${BASE}/metrics/summary`, () =>
        HttpResponse.json({ code: 40401, message: '无数据', data: null }, { status: 404 })
      )
    )
    const wrapper = mountPage()
    await fillAndSubmit(wrapper)

    expect(wrapper.find('.error').exists()).toBe(false)
    expect(wrapper.findAll('.metric-card')).toHaveLength(0)
    const empty = wrapper.get('.empty')
    expect(empty.text()).toContain('所选时间范围内无指标数据')
  })

  it('错误态（50000）展示错误与重试按钮；重试成功后渲染卡片', async () => {
    let calls = 0
    server.use(
      http.get(`${BASE}/metrics/summary`, () => {
        calls += 1
        if (calls === 1) {
          return HttpResponse.json({ code: 50000, message: '服务端内部错误', data: null }, { status: 500 })
        }
        return HttpResponse.json({ code: 0, message: 'ok', data: summaryFixture })
      })
    )
    const wrapper = mountPage()
    await fillAndSubmit(wrapper)

    expect(wrapper.find('.error').text()).toContain('服务端内部错误')
    const retry = wrapper.get('button.retry-btn')
    await retry.trigger('click')
    await flushPromises()

    expect(wrapper.find('.error').exists()).toBe(false)
    expect(wrapper.find('.metric-card[data-metric="firstScreenLoadP95"]').exists()).toBe(true)
    expect(wrapper.find('.metric-card[data-biz-code="ORDER"]').exists()).toBe(true)
    expect(calls).toBe(2)
  })

  it('必填项缺失时查询按钮禁用（恒带 customerId：空 customerId 不发请求）', async () => {
    const wrapper = mountPage()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })
})

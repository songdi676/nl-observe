import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '../src/router'
import App from '../src/App.vue'
import BusinessesPage from '../src/pages/BusinessesPage.vue'
import TracePage from '../src/pages/TracePage.vue'
import MetricsPage from '../src/pages/MetricsPage.vue'

function routerWith(path: string) {
  const router = createAppRouter(createMemoryHistory())
  return router.push(path).then(() => router)
}

describe('三页骨架', () => {
  it('BusinessesPage：客户编号 + 时间范围 + bizCode 过滤控件', async () => {
    const router = await routerWith('/businesses')
    const wrapper = mount(BusinessesPage, { global: { plugins: [router] } })
    expect(wrapper.get('h1').text()).toContain('业务列表')
    for (const name of ['customerId', 'from', 'to', 'bizCode']) {
      expect(wrapper.find(`input[name="${name}"]`).exists()).toBe(true)
    }
  })

  it('TracePage：展示当前 traceId 占位', async () => {
    const router = await routerWith('/traces/t-42')
    const wrapper = mount(TracePage, { global: { plugins: [router] } })
    expect(wrapper.get('h1').text()).toContain('业务流程')
    expect(wrapper.text()).toContain('t-42')
  })

  it('MetricsPage：指标页骨架', async () => {
    const router = await routerWith('/metrics')
    const wrapper = mount(MetricsPage, { global: { plugins: [router] } })
    expect(wrapper.get('h1').text()).toContain('指标')
  })

  it('App：导航含三页链接且渲染 RouterView', async () => {
    const router = await routerWith('/metrics')
    const wrapper = mount(App, { global: { plugins: [router] } })
    const hrefs = wrapper.findAll('nav a').map((a) => a.attributes('href'))
    expect(hrefs).toEqual(expect.arrayContaining(['/businesses', '/traces', '/metrics']))
    expect(wrapper.find('.metrics-page').exists()).toBe(true)
  })
})

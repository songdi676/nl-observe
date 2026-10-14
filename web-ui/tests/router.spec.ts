import { describe, expect, it } from 'vitest'
import { createAppRouter } from '../src/router'

describe('路由三页骨架', () => {
  it('包含业务列表/流程/指标三页路由', () => {
    const router = createAppRouter()
    const paths = router.getRoutes().map((r) => r.path).sort()
    expect(paths).toEqual(expect.arrayContaining(['/businesses', '/traces/:traceId?', '/metrics']))
  })

  it('根路径重定向到业务列表', async () => {
    const router = createAppRouter()
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/businesses')
  })
})

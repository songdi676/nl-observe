import { afterEach, describe, expect, it, vi } from 'vitest'
import { getAppConfig } from '../src/config'

afterEach(() => vi.unstubAllEnvs())

describe('getAppConfig', () => {
  it('默认配置：API 前缀 /api/observe，登录地址 /login', () => {
    const cfg = getAppConfig()
    expect(cfg.apiBaseURL).toBe('/api/observe')
    expect(cfg.loginUrl).toBe('/login')
  })

  it('LOGIN_URL 走配置项 VITE_LOGIN_URL', () => {
    vi.stubEnv('VITE_LOGIN_URL', 'https://gateway.test/sso/login')
    vi.stubEnv('VITE_API_BASE_URL', 'https://gateway.test/api/observe')
    const cfg = getAppConfig()
    expect(cfg.loginUrl).toBe('https://gateway.test/sso/login')
    expect(cfg.apiBaseURL).toBe('https://gateway.test/api/observe')
  })
})

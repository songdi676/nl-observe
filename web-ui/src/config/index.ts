export interface AppConfig {
  /** 查询 API 前缀（网关路由，详细设计 §2） */
  apiBaseURL: string
  /** 401 未授权跳转的登录地址（外部网关依赖，待澄清 #6） */
  loginUrl: string
}

export function getAppConfig(): AppConfig {
  const env = import.meta.env
  return {
    apiBaseURL: env.VITE_API_BASE_URL ?? '/api/observe',
    loginUrl: env.VITE_LOGIN_URL ?? '/login'
  }
}

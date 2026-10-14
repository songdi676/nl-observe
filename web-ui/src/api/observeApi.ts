import axios from 'axios'
import type { AxiosInstance, AxiosRequestConfig } from 'axios'
import { getAppConfig } from '../config'
import type {
  ApiResult,
  BusinessItem,
  MetricSummary,
  MetricsSummaryQuery,
  SpanDetail,
  SpanTree,
  TimeRangeQuery
} from './types'

/** 业务错误：响应 {code,message,data} 中 code 非 0（错误码见详细设计 §4.1） */
export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export interface ObserveApiOptions {
  /** API 前缀，默认取配置 VITE_API_BASE_URL */
  baseURL?: string
  /** 401 跳转登录地址，默认取配置 VITE_LOGIN_URL（外部网关依赖） */
  loginUrl?: string
  /** 请求超时（毫秒），默认 12000 = 查询端 query-timeout(10s) + 余量，保证能收到后端 50000 envelope */
  timeoutMs?: number
  /** 401 处理钩子，默认 window.location.assign(loginUrl) */
  onUnauthorized?: (loginUrl: string) => void
}

export interface ObserveApi {
  listBusinesses(customerId: string, query: TimeRangeQuery): Promise<BusinessItem[]>
  getTrace(customerId: string, traceId: string): Promise<SpanTree>
  getSpan(customerId: string, traceId: string, spanId: string): Promise<SpanDetail>
  getMetricsSummary(query: MetricsSummaryQuery): Promise<MetricSummary>
}

function defaultOnUnauthorized(loginUrl: string): void {
  window.location.assign(loginUrl)
}

/** 过滤 undefined/空串，避免可选参数以空值进入查询串 */
function cleanParams(params: Record<string, string | undefined>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') {
      out[key] = value
    }
  }
  return out
}

export function createObserveApi(options: ObserveApiOptions = {}): ObserveApi {
  const cfg = getAppConfig()
  const loginUrl = options.loginUrl ?? cfg.loginUrl
  const onUnauthorized = options.onUnauthorized ?? defaultOnUnauthorized
  const http: AxiosInstance = axios.create({
    baseURL: options.baseURL ?? cfg.apiBaseURL,
    timeout: options.timeoutMs ?? 12000
  })
  /** 并发请求同时 401 时只跳转一次 */
  let redirecting = false

  /**
   * 统一解包 {code,message,data}（详细设计 §4.1：业务错误以非 2xx 状态返回）：
   * - HTTP 401 → 跳转登录（一次性）并抛原错
   * - 响应体含 ApiResult envelope 且 code 非 0（无论 2xx 还是 4xx/5xx）→ 抛 ApiError
   * - 2xx 但 envelope 形状不符 → 抛 ApiError(-1)
   */
  async function request<T>(config: AxiosRequestConfig): Promise<T> {
    try {
      const resp = await http.request<ApiResult<T>>(config)
      const body = resp.data
      if (typeof body?.code !== 'number') {
        throw new ApiError(-1, '响应格式不符合 {code,message,data}')
      }
      if (body.code !== 0) {
        throw new ApiError(body.code, body.message)
      }
      return body.data
    } catch (e) {
      if (axios.isAxiosError(e)) {
        if (e.response?.status === 401) {
          if (!redirecting) {
            redirecting = true
            onUnauthorized(loginUrl)
          }
          throw e
        }
        const body = e.response?.data as ApiResult<unknown> | undefined
        if (body && typeof body.code === 'number' && body.code !== 0) {
          throw new ApiError(body.code, body.message)
        }
      }
      throw e
    }
  }

  return {
    listBusinesses(customerId, query) {
      return request<BusinessItem[]>({
        method: 'get',
        url: `/customers/${encodeURIComponent(customerId)}/businesses`,
        params: cleanParams({ from: query.from, to: query.to, bizCode: query.bizCode })
      })
    },
    getTrace(customerId, traceId) {
      return request<SpanTree>({
        method: 'get',
        url: `/customers/${encodeURIComponent(customerId)}/traces/${encodeURIComponent(traceId)}`
      })
    },
    getSpan(customerId, traceId, spanId) {
      return request<SpanDetail>({
        method: 'get',
        url:
          `/customers/${encodeURIComponent(customerId)}/traces/${encodeURIComponent(traceId)}` +
          `/spans/${encodeURIComponent(spanId)}`
      })
    },
    getMetricsSummary(query) {
      return request<MetricSummary>({
        method: 'get',
        url: '/metrics/summary',
        params: cleanParams({
          from: query.from,
          to: query.to,
          bizCode: query.bizCode,
          customerId: query.customerId
        })
      })
    }
  }
}

/** 默认实例（读取 VITE_API_BASE_URL / VITE_LOGIN_URL） */
export const observeApi = createObserveApi()

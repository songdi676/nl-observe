/**
 * T23 E2E 共享环境层（详设 §6.4，env-gated）。
 *
 * env-gate：E2E_BACKEND_URL 未设置 → e2eEnabled()=false，spec 内 test.skip。
 * E2E_BACKEND_URL = 查询服务基址（如 http://10.1.8.33:8081，详设 §2 接口前缀
 * /api/observe）；OTLP traces 端点缺省同主机端口 4318（receiver observe.otlp.port
 * 默认，详设 §5），E2E_OTLP_URL 可覆盖。宿主页缺省 http://127.0.0.1:4173
 * （tests/e2e/host vite dev，E2E_HOST_URL/E2E_HOST_PORT 可覆盖）。
 *
 * 查询 API 契约（详设 §2/§4.1）：统一信封 {code,message,data}；无数据 404/40401
 * ——轮询期视为「尚未到达」而非失败。
 */

export function e2eEnabled(): boolean {
  return Boolean(process.env.E2E_BACKEND_URL);
}

export function backendUrl(): string {
  const raw = process.env.E2E_BACKEND_URL;
  if (!raw) throw new Error('E2E_BACKEND_URL 未设置（E2E env-gate 关闭）');
  return raw.replace(/\/+$/, '');
}

/** OTLP/HTTP traces 端点：E2E_OTLP_URL 覆盖，缺省 E2E_BACKEND_URL 同主机端口 4318。 */
export function otlpTracesUrl(): string {
  const override = process.env.E2E_OTLP_URL;
  if (override) return override;
  const u = new URL(backendUrl());
  u.port = process.env.E2E_OTLP_PORT ?? '4318';
  u.pathname = '/v1/traces';
  u.search = '';
  return u.toString();
}

/** 宿主页地址（携带 customer/otlp 查询参数，宿主 init 时读取）。 */
export function hostUrl(params: { customer: string; otlp: string }): string {
  const base = process.env.E2E_HOST_URL ?? `http://127.0.0.1:${process.env.E2E_HOST_PORT ?? '4173'}`;
  const u = new URL(base);
  u.searchParams.set('customer', params.customer);
  u.searchParams.set('otlp', params.otlp);
  return u.toString();
}

/** 每次运行唯一客户号：隔离多次 E2E 的查询结果（businesses 按 customerId+时间窗过滤）。 */
export function newCustomerId(): string {
  return `c-e2e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/** UTC 日期（YYYY-MM-DD，查询 from/to 口径），相对今天偏移 days 天。 */
export function dayOffset(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
}

/** 详设 §2.1 data 元素 */
export interface BusinessItem {
  bizId: string;
  bizCode: string;
  traceId: string;
  startTime: string;
  effectiveDurationMs: number;
  status: string;
  endReason: string;
}

/** 详设 §2.2 spans 元素（E2E 断言所需子集） */
export interface SpanNode {
  spanId: string;
  parentSpanId: string;
  name: string;
  kind: number;
  startTime: string;
  endTime: string;
  statusCode: number;
  durationMs: number;
  effectiveDurationMs: number;
  attributes: Record<string, unknown>;
  events: { name: string }[];
}

/** 详设 §2.4 data */
export interface MetricSummary {
  firstScreenLoadP95: number | null;
  perBiz: { bizCode: string; effectiveDurationP95: number; successRate: number; count: number }[];
}

async function apiGet<T>(path: string): Promise<ApiEnvelope<T> | null> {
  const res = await fetch(`${backendUrl()}${path}`);
  if (res.status === 404) return null; // 40401 无数据：轮询期视为尚未到达
  if (!res.ok) throw new Error(`查询 API ${path} HTTP ${res.status}`);
  return (await res.json()) as ApiEnvelope<T>;
}

export async function listBusinesses(customerId: string, from: string, to: string): Promise<BusinessItem[]> {
  const env = await apiGet<BusinessItem[]>(
    `/api/observe/customers/${encodeURIComponent(customerId)}/businesses?from=${from}&to=${to}`,
  );
  return env?.data ?? [];
}

export async function getTrace(customerId: string, traceId: string): Promise<SpanNode[] | null> {
  const env = await apiGet<{ traceId: string; spans: SpanNode[] }>(
    `/api/observe/customers/${encodeURIComponent(customerId)}/traces/${encodeURIComponent(traceId)}`,
  );
  return env?.data.spans ?? null;
}

export async function getMetricsSummary(from: string, to: string, customerId: string): Promise<MetricSummary | null> {
  const env = await apiGet<MetricSummary>(
    `/api/observe/metrics/summary?from=${from}&to=${to}&customerId=${encodeURIComponent(customerId)}`,
  );
  return env?.data ?? null;
}

export interface PollOptions {
  timeoutMs?: number;
  intervalMs?: number;
  label: string;
}

/**
 * 轮询直到 probe 返回真值（全链路 receiver→Kafka→storage→Databend 有管道时延）。
 * probe 抛错不终止轮询（内网抖动容忍），超时后带最后一次错误抛出。
 */
export async function pollUntil<T>(
  probe: () => Promise<T | null | undefined>,
  opts: PollOptions,
): Promise<T> {
  const timeoutMs = opts.timeoutMs ?? 120_000;
  const intervalMs = opts.intervalMs ?? 3_000;
  const deadline = Date.now() + timeoutMs;
  let last = 'probe 从未返回真值';
  for (;;) {
    try {
      const v = await probe();
      if (v) return v;
    } catch (err) {
      last = String(err);
    }
    if (Date.now() > deadline) {
      throw new Error(`轮询超时（${timeoutMs}ms，${opts.label}）：${last}`);
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
}

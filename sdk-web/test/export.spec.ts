/**
 * T17 单测：OTLP/HTTP 导出接线 + init 配置（endpoint / exportTimeoutMs / restPathFilter）
 * + 降级策略（endpoint 不可达 → console.debug 静默降级，不抛错、不阻断业务办理，NFR-03）。
 *
 * node 环境：无 navigator.sendBeacon → OTLP/HTTP 传输回退 fetch（stub 模拟「端点不可达」）。
 * 「不可达」= 传输层抛错（ECONNREFUSED/超时 abort）→ exporter 回调 FAILED →
 * DropCounting 计 dropped_spans、SDK 日志经 diag 落到 console.debug——业务 span 照常本地生成。
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { InMemorySpanExporter, SimpleSpanProcessor, type ReadableSpan } from '@opentelemetry/sdk-trace-base';
import { NlObserveClient, DEFAULT_EXPORT_TIMEOUT_MS } from '../src/core/client';

const OTLP_TRACES = 'http://127.0.0.1:4318/v1/traces';
const OTLP_METRICS = 'http://127.0.0.1:4318/v1/metrics';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  delete (globalThis as Record<string, unknown>).__NL_OBSERVE__;
});

/** stub 全局 fetch：记录 URL 后一律以 ECONNREFUSED 失败（模拟 endpoint 不可达）。 */
function rejectingFetch(): { calls: string[]; fn: unknown } {
  const calls: string[] = [];
  const fn = (async (input: RequestInfo | URL): Promise<Response> => {
    calls.push(typeof input === 'string' ? input : String(input));
    throw new TypeError('fetch failed: ECONNREFUSED');
  }) as unknown as typeof fetch;
  return { calls, fn };
}

describe('T17 init 配置：endpoint / exportTimeoutMs / restPathFilter', () => {
  it('init({endpoint}) 接通 OTLP/HTTP traces/metrics 导出（与 otlpUrl 同语义，endpoint 优先）', async () => {
    const { calls, fn } = rejectingFetch();
    vi.stubGlobal('fetch', fn);
    const c = new NlObserveClient();
    c.init({
      serviceName: 's',
      customerId: 'C-1',
      endpoint: OTLP_TRACES,
      otlpUrl: 'http://127.0.0.1:9999/v1/traces', // endpoint 优先
      batchTimeoutMs: 60000,
      instrumentRest: false,
    });
    expect(c.resolvedOptions.endpoint).toBe(OTLP_TRACES);
    c.signIn('C-1');
    const bizId = c.startBusiness('ORDER');
    expect(bizId).toBeDefined();
    c.endBusiness(bizId!, { status: 'success' });
    await c.shutdown(); // shutdown flush → 两次 OTLP/HTTP 导出（traces + metrics）
    expect(calls).toContain(OTLP_TRACES);
    expect(calls).toContain(OTLP_METRICS);
    expect(calls.some((u) => u.includes('127.0.0.1:9999'))).toBe(false);
  });

  it('otlpUrl 向后兼容：仅传 otlpUrl 时按 otlpUrl 接线（既有 T13-T16 宿主不受影响）', async () => {
    const { calls, fn } = rejectingFetch();
    vi.stubGlobal('fetch', fn);
    const c = new NlObserveClient();
    c.init({ serviceName: 's', customerId: 'C-1', otlpUrl: OTLP_TRACES, batchTimeoutMs: 60000 });
    expect(c.resolvedOptions.endpoint).toBe(OTLP_TRACES);
    c.signIn('C-1');
    const bizId = c.startBusiness('ORDER')!;
    c.endBusiness(bizId, { status: 'success' });
    await c.shutdown();
    expect(calls).toContain(OTLP_TRACES);
  });

  it('restPathFilter：仅命中过滤器的 URL 产生 REST span（query 已去；OTLP 端点自排除仍生效）', async () => {
    const { fn } = rejectingFetch();
    vi.stubGlobal('fetch', fn);
    const exporter = new InMemorySpanExporter();
    const c = new NlObserveClient();
    c.init({
      serviceName: 's',
      customerId: 'C-1',
      endpoint: OTLP_TRACES,
      batchTimeoutMs: 60000,
      restPathFilter: (url) => url.includes('/api/'),
      spanProcessors: [new SimpleSpanProcessor(exporter)],
    });
    c.signIn('C-1');
    const bizId = c.startBusiness('ORDER')!;
    await fetch('http://svc.local/api/orders?token=secret').catch(() => undefined);
    await fetch('http://svc.local/static/app.js').catch(() => undefined);
    const restSpans = exporter.getFinishedSpans().filter((s: ReadableSpan) => s.name === 'HTTP GET');
    expect(restSpans).toHaveLength(1);
    expect(restSpans[0].attributes['http.route']).toBe('/api/orders');
    c.endBusiness(bizId, { status: 'success' });
    await c.shutdown();
  });

  it('业务 root span 以 ROOT_CONTEXT 为父：不同业务 traceId 不同（T17 验收 1 闭环）', async () => {
    const { fn } = rejectingFetch();
    vi.stubGlobal('fetch', fn);
    const exporter = new InMemorySpanExporter();
    const c = new NlObserveClient();
    c.init({
      serviceName: 's',
      customerId: 'C-1',
      endpoint: OTLP_TRACES,
      batchTimeoutMs: 60000,
      instrumentRest: false,
      spanProcessors: [new SimpleSpanProcessor(exporter)],
    });
    c.signIn('C-1');
    const b1 = c.startBusiness('ORDER')!;
    const b2 = c.startBusiness('PAY')!; // 自动挂起 ORDER
    c.endBusiness(b1, { status: 'success' });
    c.endBusiness(b2, { status: 'success' });
    const roots = exporter.getFinishedSpans().filter((s: ReadableSpan) => s.name.startsWith('biz.business.'));
    expect(roots).toHaveLength(2);
    const traceIds = roots.map((s) => s.spanContext().traceId);
    expect(new Set(traceIds).size).toBe(2);
    await c.shutdown();
  });

  it('exportTimeoutMs 缺省 5000ms（DEFAULT_EXPORT_TIMEOUT_MS）', () => {
    expect(DEFAULT_EXPORT_TIMEOUT_MS).toBe(5000);
    const c = new NlObserveClient();
    c.init({ serviceName: 's' });
    // 未配置 endpoint/timeout → 不影响既有解析结果
    expect(c.resolvedOptions.batchTimeoutMs).toBe(2000);
  });

  it('exportTimeoutMs：挂起的 OTLP 导出按超时 abort 并降级，shutdown 不挂死', async () => {
    let aborts = 0;
    const fn = ((
      _input: RequestInfo | URL,
      init?: RequestInit,
    ): Promise<Response> =>
      new Promise<Response>((_resolve, reject) => {
        const sig = init?.signal;
        if (sig) {
          const onAbort = (): void => {
            aborts += 1;
            reject(new Error('OTLP export aborted (timeout)'));
          };
          if (sig.aborted) onAbort();
          else sig.addEventListener('abort', onAbort, { once: true });
        }
        // 永不 resolve：模拟接收端挂起
      })) as unknown as typeof fetch;
    vi.stubGlobal('fetch', fn);
    const c = new NlObserveClient();
    c.init({
      serviceName: 's',
      customerId: 'C-1',
      endpoint: OTLP_TRACES,
      batchTimeoutMs: 60000,
      exportTimeoutMs: 100,
      instrumentRest: false,
    });
    c.signIn('C-1');
    const bizId = c.startBusiness('ORDER')!;
    c.endBusiness(bizId, { status: 'success' });
    const t0 = Date.now();
    await c.shutdown();
    expect(Date.now() - t0).toBeLessThan(5000);
    expect(aborts).toBeGreaterThanOrEqual(1);
  });
});

describe('T17 降级策略：endpoint 不可达（NFR-03，详设 §4.2）', () => {
  it('init/签入/业务办理/REST/结束/关闭 全流程无异常抛出；降级日志走 console.debug', async () => {
    const { fn } = rejectingFetch();
    vi.stubGlobal('fetch', fn);
    const debug = vi.spyOn(console, 'debug').mockImplementation(() => {});
    const c = new NlObserveClient();
    c.init({ serviceName: 's', customerId: 'C-1', endpoint: OTLP_TRACES, batchTimeoutMs: 60000 });
    c.signIn('C-1');
    const bizId = c.startBusiness('ORDER');
    expect(bizId).toBeDefined();
    await fetch('http://svc.local/api/orders').catch(() => undefined); // 业务请求照常放行
    c.endBusiness(bizId!, { status: 'success' });
    await expect(c.shutdown()).resolves.toBeUndefined(); // 导出失败不得使 shutdown 抛错
    expect(
      debug.mock.calls.some((a) => String(a[0]).includes('[nl-observe]') && String(a[0]).includes('degraded')),
    ).toBe(true);
  });

  it('业务 span 本地生成不丢失：不可达期间 root span 属性完整（status/end_reason 落位）', async () => {
    const { fn } = rejectingFetch();
    vi.stubGlobal('fetch', fn);
    const exporter = new InMemorySpanExporter();
    const c = new NlObserveClient();
    c.init({
      serviceName: 's',
      customerId: 'C-1',
      endpoint: OTLP_TRACES,
      batchTimeoutMs: 60000,
      spanProcessors: [new SimpleSpanProcessor(exporter)],
    });
    c.signIn('C-1');
    const bizId = c.startBusiness('ORDER')!;
    c.endBusiness(bizId, { status: 'success', endReason: 'explicit' });
    const root = exporter.getFinishedSpans().find((s: ReadableSpan) => s.name === 'biz.business.ORDER');
    expect(root).toBeDefined();
    expect(root!.attributes['biz.status']).toBe('success');
    expect(root!.attributes['biz.end_reason']).toBe('explicit');
    expect(root!.attributes['nl.customer_id']).toBe('C-1');
    await c.shutdown();
  });
});

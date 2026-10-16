/**
 * T14 core 单测：fetch/XHR 自动埋点 + trackRest 手动兜底（详设 §1.5 rest/autoprofile）。
 *
 * 覆盖任务测试用例：
 * 1. fetch 成功/失败均产生 REST span（name='HTTP {METHOD}'，parent=当前 ACTIVE 业务 root span：
 *    同 traceId + parentSpanId=root spanId）；无 ACTIVE 业务（未起/已结束/未签入）无 span
 * 2. URL 含 query → http.route 已去 query（绝对 URL 仅留 path）；attributes 键集合=白名单 4 项
 *    （http.request.method / http.route / http.response.status_code / nl.duration_ms）；
 *    query 值（PII 面）绝不进入 attributes
 * 3. trackRest(method, route, statusCode, durationMs) 产生与自动埋点等价 span（同命名/父子/属性形态）
 * 另：XHR（loadend 驱动）同口径；OTLP 自身端点自排除（防自埋点递归）；shutdown 还原全局 fetch/XHR。
 *
 * node 环境：测试先以桩替换 globalThis.fetch / 以假 XHR 替换 globalThis.XMLHttpRequest，
 * client.init() 的自动埋点包装桩对象，shutdown() 还原（与真实宿主行为同构）。
 * span 捕获：SimpleSpanProcessor+InMemorySpanExporter（已结束）+ 自写 processor onStart（活 span 取 root spanId）。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { type ReadableSpan, type Span, type SpanProcessor } from '@opentelemetry/sdk-trace-base';
import { InMemorySpanExporter, SimpleSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { NlObserveClient } from '../src/core/client';
import {
  ATTR_HTTP_REQUEST_METHOD,
  ATTR_HTTP_RESPONSE_STATUS_CODE,
  ATTR_HTTP_ROUTE,
  ATTR_NL_DURATION_MS,
  REST_SPAN_NAME_PREFIX,
} from '../src/core/rest/autoprofile';

/** REST span 属性白名单 4 项（与 backend AttributeKeys.REST_SPAN_WHITELIST 一致，排序后断言）。 */
const REST_ATTR_KEYS_SORTED = [
  ATTR_HTTP_REQUEST_METHOD,
  ATTR_HTTP_RESPONSE_STATUS_CODE,
  ATTR_HTTP_ROUTE,
  ATTR_NL_DURATION_MS,
].sort();

interface Captured {
  started: Map<string, Span>;
  processor: SpanProcessor;
}

function makeCaptureProcessor(): Captured {
  const started = new Map<string, Span>();
  const processor: SpanProcessor = {
    onStart: (span) => {
      started.set(span.name, span);
    },
    onEnd: () => undefined,
    shutdown: () => Promise.resolve(),
    forceFlush: () => Promise.resolve(),
  };
  return { started, processor };
}

interface Harness {
  client: NlObserveClient;
  spans: InMemorySpanExporter;
  cap: Captured;
}

function makeClient(opts: { clock?: () => number; otlpUrl?: string } = {}): Harness {
  const spans = new InMemorySpanExporter();
  const cap = makeCaptureProcessor();
  const client = new NlObserveClient();
  client.init({
    serviceName: 'test',
    customerId: 'C-1',
    otlpUrl: opts.otlpUrl,
    // 长超时：避免 BatchSpanProcessor 测试期间触发真实 OTLP 导出（本用例无 otlpUrl 时该处理器不存在）
    batchTimeoutMs: 3_600_000,
    clock: opts.clock,
    spanProcessors: [new SimpleSpanProcessor(spans), cap.processor],
  });
  return { client, spans, cap };
}

/** 取 REST 已结束 span（排除业务 root span）。 */
function restSpans(h: Harness): ReadableSpan[] {
  return h.spans
    .getFinishedSpans()
    .filter((s) => s.name.startsWith(REST_SPAN_NAME_PREFIX));
}

/** 当前 ACTIVE 业务 root span 的 spanId（经捕获 processor 的活 span 读取）。 */
function activeRoot(h: Harness, bizName: string): { spanId: string; traceId: string } {
  const root = h.cap.started.get(bizName);
  expect(root).toBeDefined();
  return { spanId: root!.spanContext().spanId, traceId: root!.spanContext().traceId };
}

// ---- 全局 fetch/XHR 桩（before init 注入，shutdown 后还原） ----

const realFetch = globalThis.fetch;
type FetchStub = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

async function withFetchStub(impl: (url: string, init?: RequestInit) => Promise<unknown>, fn: () => Promise<void>): Promise<void> {
  const stub: FetchStub = async (input, init) => {
    const url =
      typeof input === 'string'
        ? input
        : typeof URL !== 'undefined' && input instanceof URL
          ? input.href
          : typeof Request !== 'undefined' && input instanceof Request
            ? input.url
            : String(input);
    return (await impl(url, init)) as Response;
  };
  (globalThis as Record<string, unknown>).fetch = stub;
  try {
    await fn();
  } finally {
    if (realFetch) {
      (globalThis as Record<string, unknown>).fetch = realFetch;
    } else {
      delete (globalThis as Record<string, unknown>).fetch;
    }
  }
}

/** 假 XMLHttpRequest（loadend 异步触发；open 记录 method/url，send 记录 body 不得外泄）。 */
class FakeXHR {
  status = 0;
  method = '';
  url = '';
  lastBody: unknown = undefined;
  private listeners: Record<string, Array<() => void>> = {};

  addEventListener(type: string, listener: EventListenerOrEventListenerObject): void {
    const fn = typeof listener === 'function' ? (listener as () => void) : listener.handleEvent.bind(listener);
    (this.listeners[type] ??= []).push(fn as () => void);
  }

  removeEventListener(): void {
    this.listeners = {};
  }

  open(method: string, url: string): void {
    this.method = method;
    this.url = String(url);
  }

  send(body?: unknown): void {
    this.lastBody = body;
    const status = (this as FakeXHR & { __status?: number }).__status ?? 200;
    Promise.resolve().then(() => {
      this.status = status;
      for (const fn of this.listeners['loadend'] ?? []) fn();
    });
  }

  static withStatus(status: number): new () => FakeXHR {
    class S extends FakeXHR {}
    (S.prototype as unknown as { __status: number }).__status = status;
    return S;
  }
}

function installFakeXHR(Class: new () => FakeXHR): void {
  (globalThis as Record<string, unknown>).XMLHttpRequest = Class;
}

afterEach(() => {
  if (realFetch) {
    (globalThis as Record<string, unknown>).fetch = realFetch;
  } else {
    delete (globalThis as Record<string, unknown>).fetch;
  }
  delete (globalThis as Record<string, unknown>).XMLHttpRequest;
  vi.restoreAllMocks();
});

describe('T14 fetch 自动埋点：REST span 生成与 parent 归属', () => {
  it('fetch 成功：REST span name=HTTP {METHOD}，parent=ACTIVE 业务 root span（同 traceId，parentSpanId=root spanId），白名单 4 属性', async () => {
    let t = 1_000_000;
    await withFetchStub(
      async () => {
        t += 37; // 请求进行中推进时钟 → duration_ms=37
        return { status: 200 } as Response;
      },
      async () => {
        const h = makeClient({ clock: () => t });
        h.client.signIn('C-1');
        const bizId = h.client.startBusiness('ORDER');
        expect(bizId).toBeDefined();
        const root = activeRoot(h, 'biz.business.ORDER');

        await globalThis.fetch!('https://api.test/api/orders?token=secret-123', { method: 'post' });

        const [rest] = restSpans(h);
        expect(rest).toBeDefined();
        expect(rest!.name).toBe('HTTP POST');
        // parent=业务 root span：同 trace + parentSpanId=root spanId
        expect(rest!.spanContext().traceId).toBe(root.traceId);
        expect(rest!.parentSpanContext!.spanId).toBe(root.spanId);
        // 属性仅白名单 4 项（键集合断言）
        expect(Object.keys(rest!.attributes).sort()).toEqual(REST_ATTR_KEYS_SORTED);
        expect(rest!.attributes[ATTR_HTTP_REQUEST_METHOD]).toBe('POST');
        expect(rest!.attributes[ATTR_HTTP_ROUTE]).toBe('/api/orders'); // 去 query
        expect(rest!.attributes[ATTR_HTTP_RESPONSE_STATUS_CODE]).toBe(200);
        expect(rest!.attributes[ATTR_NL_DURATION_MS]).toBe(37);
        // PII 白名单：query 值绝不进入任一属性值
        for (const v of Object.values(rest!.attributes)) {
          expect(String(v)).not.toContain('secret-123');
          expect(String(v)).not.toContain('token');
        }
        await h.client.shutdown();
      },
    );
  });

  it('fetch 失败（网络错误）：仍产生 REST span（parent 正确，status_code=0）且原错误继续抛出', async () => {
    await withFetchStub(async () => {
      throw new TypeError('network down');
    }, async () => {
      const h = makeClient();
      h.client.signIn('C-1');
      h.client.startBusiness('ORDER');
      const root = activeRoot(h, 'biz.business.ORDER');

      await expect(globalThis.fetch!('https://api.test/api/orders')).rejects.toThrow('network down');

      const [rest] = restSpans(h);
      expect(rest).toBeDefined();
      expect(rest!.name).toBe('HTTP GET'); // 缺省 method=GET
      expect(rest!.spanContext().traceId).toBe(root.traceId);
      expect(rest!.parentSpanContext!.spanId).toBe(root.spanId);
      expect(rest!.attributes[ATTR_HTTP_RESPONSE_STATUS_CODE]).toBe(0);
      await h.client.shutdown();
    });
  });

  it('无 ACTIVE 业务不上报：未起业务 / 业务已结束 / 未签入，fetch 均无 REST span', async () => {
    await withFetchStub(async () => ({ status: 200 }) as Response, async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

      // 1) 已签入但未起业务
      const h1 = makeClient();
      h1.client.signIn('C-1');
      await globalThis.fetch!('https://api.test/a');
      expect(restSpans(h1)).toHaveLength(0);
      await h1.client.shutdown();

      // 2) 业务已结束（ENDED 移出实例表）
      const h2 = makeClient();
      h2.client.signIn('C-1');
      h2.client.startBusiness('ORDER');
      h2.client.endBusiness('ORDER', { status: 'success' });
      await globalThis.fetch!('https://api.test/b');
      expect(restSpans(h2)).toHaveLength(0);
      await h2.client.shutdown();

      // 3) 未签入（startBusiness no-op → 无 ACTIVE）
      const h3 = makeClient();
      h3.client.startBusiness('ORDER');
      await globalThis.fetch!('https://api.test/c');
      expect(restSpans(h3)).toHaveLength(0);
      await h3.client.shutdown();

      expect(warn).toHaveBeenCalled(); // 未签入 startBusiness warn（既有 T13 语义）
    });
  });
});

describe('T14 路由规范化（AC-04 去 query）', () => {
  it('绝对 URL 含 query → http.route 仅留 path；attributes 键集合=白名单 4 项', async () => {
    await withFetchStub(async () => ({ status: 201 }) as Response, async () => {
      const h = makeClient();
      h.client.signIn('C-1');
      h.client.startBusiness('ORDER');
      await globalThis.fetch!('https://api.test/v1/orders/9f3k9x?from=2026-01-01&token=abc#frag');

      const [rest] = restSpans(h);
      expect(rest).toBeDefined();
      expect(rest!.attributes[ATTR_HTTP_ROUTE]).toBe('/v1/orders/9f3k9x');
      expect(Object.keys(rest!.attributes).sort()).toEqual(REST_ATTR_KEYS_SORTED);
      await h.client.shutdown();
    });
  });

  it('相对 URL（/api/x?y=1）→ http.route=/api/x', async () => {
    await withFetchStub(async () => ({ status: 200 }) as Response, async () => {
      const h = makeClient();
      h.client.signIn('C-1');
      h.client.startBusiness('ORDER');
      await globalThis.fetch!('/api/relative?x=1&y=2');

      const [rest] = restSpans(h);
      expect(rest!.attributes[ATTR_HTTP_ROUTE]).toBe('/api/relative');
      await h.client.shutdown();
    });
  });
});

describe('T14 XHR 自动埋点', () => {
  it('XMLHttpRequest（open+send，loadend）产生 REST span：method/route/status 正确，parent=ACTIVE root span', async () => {
    installFakeXHR(FakeXHR);
    const h = makeClient();
    h.client.signIn('C-1');
    h.client.startBusiness('ORDER');
    const root = activeRoot(h, 'biz.business.ORDER');

    const xhr = new (globalThis as unknown as { XMLHttpRequest: new () => FakeXHR }).XMLHttpRequest();
    xhr.open('DELETE', 'https://api.test/api/items/42?audit=zzz');
    xhr.send('PII-BODY');
    await new Promise((r) => setTimeout(r, 20)); // 等待 loadend 微任务

    const [rest] = restSpans(h);
    expect(rest).toBeDefined();
    expect(rest!.name).toBe('HTTP DELETE');
    expect(rest!.spanContext().traceId).toBe(root.traceId);
    expect(rest!.parentSpanContext!.spanId).toBe(root.spanId);
    expect(rest!.attributes[ATTR_HTTP_REQUEST_METHOD]).toBe('DELETE');
    expect(rest!.attributes[ATTR_HTTP_ROUTE]).toBe('/api/items/42'); // 去 query
    expect(rest!.attributes[ATTR_HTTP_RESPONSE_STATUS_CODE]).toBe(200);
    expect(Object.keys(rest!.attributes).sort()).toEqual(REST_ATTR_KEYS_SORTED);
    // body 不进入 attributes
    for (const v of Object.values(rest!.attributes)) {
      expect(String(v)).not.toContain('PII-BODY');
    }
    await h.client.shutdown();
  });

  it('XHR 非 2xx（status=500）：status_code=500 如实记录', async () => {
    installFakeXHR(FakeXHR.withStatus(500));
    const h = makeClient();
    h.client.signIn('C-1');
    h.client.startBusiness('ORDER');
    const xhr = new (globalThis as unknown as { XMLHttpRequest: new () => FakeXHR }).XMLHttpRequest();
    xhr.open('GET', '/api/failing');
    xhr.send();
    await new Promise((r) => setTimeout(r, 20));

    const [rest] = restSpans(h);
    expect(rest!.attributes[ATTR_HTTP_RESPONSE_STATUS_CODE]).toBe(500);
    await h.client.shutdown();
  });

  it('XHR 无 ACTIVE 业务 → 不监听、无 span', async () => {
    installFakeXHR(FakeXHR);
    const h = makeClient();
    h.client.signIn('C-1');
    const xhr = new (globalThis as unknown as { XMLHttpRequest: new () => FakeXHR }).XMLHttpRequest();
    xhr.open('GET', '/api/no-biz');
    xhr.send();
    await new Promise((r) => setTimeout(r, 20));
    expect(restSpans(h)).toHaveLength(0);
    await h.client.shutdown();
  });
});

describe('T14 trackRest 手动兜底（行为与自动埋点一致）', () => {
  it('trackRest 产生等价 span：name=HTTP {METHOD}、parent=ACTIVE root、白名单 4 属性、durationMs 如实', () => {
    const h = makeClient();
    h.client.signIn('C-1');
    h.client.startBusiness('ORDER');
    const root = activeRoot(h, 'biz.business.ORDER');

    h.client.trackRest('PUT', '/api/legacy?x=9', 204, 10);

    const [rest] = restSpans(h);
    expect(rest).toBeDefined();
    expect(rest!.name).toBe('HTTP PUT');
    expect(rest!.spanContext().traceId).toBe(root.traceId);
    expect(rest!.parentSpanContext!.spanId).toBe(root.spanId);
    expect(Object.keys(rest!.attributes).sort()).toEqual(REST_ATTR_KEYS_SORTED);
    expect(rest!.attributes[ATTR_HTTP_REQUEST_METHOD]).toBe('PUT');
    expect(rest!.attributes[ATTR_HTTP_ROUTE]).toBe('/api/legacy'); // 手动 route 同样去 query
    expect(rest!.attributes[ATTR_HTTP_RESPONSE_STATUS_CODE]).toBe(204);
    expect(rest!.attributes[ATTR_NL_DURATION_MS]).toBe(10);
    // 与自动埋点同形：span 时长=durationMs（HrTime=[秒,纳秒]，10ms=[0,10_000_000]）
    expect(rest!.duration).toEqual([0, 10_000_000]);
  });

  it('trackRest 无 ACTIVE 业务 → 无 span（与自动埋点口径一致）', () => {
    const h = makeClient();
    h.client.signIn('C-1');
    h.client.trackRest('GET', '/api/x', 200, 5);
    expect(restSpans(h)).toHaveLength(0);
  });

  it('未 init 调 trackRest → warn+no-op', () => {
    const c = new NlObserveClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    c.trackRest('GET', '/x', 200, 1);
    expect(warn.mock.calls.some((call) => String(call[0]).includes('trackRest'))).toBe(true);
  });
});

describe('T14 自排除与生命周期', () => {
  it('OTLP 端点自请求不上报（防自埋点递归）', async () => {
    const otlpUrl = 'http://127.0.0.1:4318/v1/traces';
    const calls: string[] = [];
    await withFetchStub(async (url) => {
      calls.push(url);
      return { status: 200 } as Response;
    }, async () => {
      const h = makeClient({ otlpUrl });
      h.client.signIn('C-1');
      h.client.startBusiness('ORDER');
      await globalThis.fetch!(otlpUrl);
      expect(calls).toEqual([otlpUrl]); // 原始 fetch 仍被调用
      expect(restSpans(h)).toHaveLength(0); // 但无 REST span
      // 不 shutdown：otlpUrl 客户端 shutdown 会触发 OTLP 导出 flush（测试环境不可达），
      // 且本用例已通过长 batchTimeoutMs 避免期间 flush
    });
  });

  it('shutdown 还原全局 fetch/XHR（不残留包装层）', async () => {
    installFakeXHR(FakeXHR);
    const xhrc = globalThis.XMLHttpRequest;
    await withFetchStub(async () => ({ status: 200 }) as Response, async () => {
      const stub = globalThis.fetch;
      const h = makeClient();
      expect(globalThis.fetch).not.toBe(stub); // init 已包装
      h.client.signIn('C-1');
      await globalThis.fetch!('https://api.test/a');
      await h.client.shutdown();
      expect(globalThis.fetch).toBe(stub); // shutdown 还原
    });
    expect(globalThis.XMLHttpRequest).toBe(xhrc); // XHR 包装随 shutdown 还原
  });
});

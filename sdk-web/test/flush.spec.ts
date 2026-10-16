/**
 * T15 core 单测：性能指标（nl.first_screen_load）+ sendBeacon flush + nl.sdk.flush 上报。
 *
 * 覆盖任务验收/测试用例：
 * 1. nl.first_screen_load 每页仅一个 data point（LCP PerformanceObserver mock；
 *    无 PO 时 load 事件兜底，仍仅一个）
 * 2. pagehide / visibilitychange=hidden → forceFlush 被调用（visible 不触发；hidden+pagehide 去重）
 * 3. forceFlush reject → nl.sdk.flush{flush:'beacon', result:'fail', dropped_spans:N} 上报；
 *    二次失败（flush 指标自身再导出失败）仅 console.warn，不抛错
 * 4. forceFlush 挂起 → result:'timeout'（flushTimeoutMs 可配）；成功 → result:'ok'
 * 5. transport：浏览器 navigator.sendBeacon（Blob application/json，OTLP/HTTP JSON 文本口径）；
 *    sendBeacon 拒绝 → reject；OTLP/JSON 载荷 traceId 为 hex
 * 6. DropCountingSpanExporter：失败批次计入 dropped、成功清零、在途批次计入
 * 7. SendBeaconMetricExporter：Counter 必须 DELTA（T13 锁定，勿回归）；OTLP/JSON sum 载荷
 *
 * node 环境（WebTracerProvider/MeterProvider 构造器不触碰 window；不全局 register——POC 发现 4）。
 * forceFlush 观测：原型 spy（WebTracerProvider/MeterProvider）；drop 计数走真实
 * BatchSpanProcessor + 真实 sendBeacon→fetch 回退 transport（死端口 127.0.0.1:1 快速 ECONNREFUSED）。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ExportResultCode, type ExportResult } from '@opentelemetry/core';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  AggregationTemporality,
  DataPointType,
  InMemoryMetricExporter,
  InstrumentType,
  MeterProvider,
  PeriodicExportingMetricReader,
  type DataPoint,
} from '@opentelemetry/sdk-metrics';
import type { ReadableSpan, SpanExporter, SpanProcessor } from '@opentelemetry/sdk-trace-base';
import type { Attributes } from '@opentelemetry/api';
import { NlObserveClient } from '../src/core/client';
import {
  ATTR_DROPPED_SPANS,
  ATTR_FLUSH,
  ATTR_FLUSH_RESULT,
  FLUSH_MODE_BEACON,
  METRIC_FIRST_SCREEN_LOAD,
  METRIC_SDK_FLUSH,
  type FlushResult,
  type LcpEntryList,
  type LcpObserverCtor,
  type WindowLike,
} from '../src/core/perf/metrics';
import {
  DEFAULT_FLUSH_TIMEOUT_MS,
  DropCountingSpanExporter,
  SendBeaconMetricExporter,
  SendBeaconSpanExporter,
  createOtlpHttpBeaconTransport,
  metricsUrlFromTracesUrl,
  safeUrlForLog,
  setupFlushHook,
} from '../src/core/flush';

/** 死端口：fetch 回退 transport 快速 ECONNREFUSED（触发 flush fail 路径） */
const DEAD_OTLP_URL = 'http://127.0.0.1:1/v1/traces';
/** 远大于测试时长的批量/导出间隔：测试期间不触发任何后台导出，只由 forceFlush 驱动 */
const LONG_DELAY_MS = 3_600_000;

interface FakeWindow extends WindowLike {
  // WindowLike.visibilityState 按真实 DOM 建模为只读属性；fake 需运行时改写，此处放宽为可变属性
  visibilityState: string;
  dispatch(type: string): void;
  listenerCount(type: string): number;
  setVisibility(v: 'visible' | 'hidden'): void;
}

function makeFakeWindow(initial: 'visible' | 'hidden' = 'visible'): FakeWindow {
  const listeners = new Map<string, Set<() => void>>();
  const win: FakeWindow = {
    addEventListener: (type, handler) => {
      let set = listeners.get(type);
      if (!set) {
        set = new Set();
        listeners.set(type, set);
      }
      set.add(handler);
    },
    removeEventListener: (type, handler) => {
      listeners.get(type)?.delete(handler);
    },
    visibilityState: initial,
    dispatch: (type) => {
      for (const h of [...(listeners.get(type) ?? [])]) h();
    },
    listenerCount: (type) => listeners.get(type)?.size ?? 0,
    setVisibility: (v) => {
      win.visibilityState = v;
    },
  };
  return win;
}

interface FlushHarness {
  client: NlObserveClient;
  metrics: InMemoryMetricExporter;
  reader: PeriodicExportingMetricReader;
  win: FakeWindow;
}

function makeFlushClient(opts: {
  otlpUrl?: string;
  flushTimeoutMs?: number;
  performanceObserver?: LcpObserverCtor | null;
  loadTimeMs?: () => number;
  win: FakeWindow;
}): FlushHarness {
  const metrics = new InMemoryMetricExporter(AggregationTemporality.DELTA);
  const reader = new PeriodicExportingMetricReader({ exporter: metrics, exportIntervalMillis: LONG_DELAY_MS });
  const client = new NlObserveClient();
  client.init({
    serviceName: 't15',
    customerId: 'C-1',
    otlpUrl: opts.otlpUrl,
    batchTimeoutMs: LONG_DELAY_MS,
    flushTimeoutMs: opts.flushTimeoutMs,
    performanceObserver: opts.performanceObserver,
    loadTimeMs: opts.loadTimeMs,
    windowLike: opts.win,
    metricReaders: [reader],
  });
  return { client, metrics, reader, win: opts.win };
}

/** 收集指定指标名的全部 data point（断言 sum 形态，钉 T13 契约）。 */
function collectMetric(exporter: InMemoryMetricExporter, name: string): Array<{ value: number; attributes: Attributes }> {
  const out: Array<{ value: number; attributes: Attributes }> = [];
  for (const rm of exporter.getMetrics()) {
    for (const sm of rm.scopeMetrics) {
      for (const m of sm.metrics) {
        if (m.descriptor.name !== name) continue;
        expect(m.dataPointType).toBe(DataPointType.SUM);
        for (const dp of m.dataPoints as DataPoint<number>[]) {
          out.push({ value: dp.value, attributes: dp.attributes });
        }
      }
    }
  }
  return out;
}

/** 临时替换 globalThis.navigator（Node 21+ 有只读 navigator 全局，需 defineProperty 覆盖）。 */
async function withFakeNavigator(fake: unknown, fn: () => Promise<void>): Promise<void> {
  const original = Reflect.getOwnPropertyDescriptor(globalThis, 'navigator');
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: fake });
  try {
    await fn();
  } finally {
    if (original) {
      Object.defineProperty(globalThis, 'navigator', original);
    } else {
      delete (globalThis as Record<string, unknown>).navigator;
    }
  }
}

function fakeSpan(): ReadableSpan {
  return {
    spanContext: () => ({ traceId: 'a'.repeat(32), spanId: 'b'.repeat(16), traceFlags: 1 }),
  } as unknown as ReadableSpan;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('T15 nl.first_screen_load（每页仅一个 data point）', () => {
  it('LCP 多候选批次（PerformanceObserver mock）→ 仅一个 data point，取首批末条；load 兜底随之失效', async () => {
    const win = makeFakeWindow();
    let disconnected = 0;
    class MockLcpObserver {
      constructor(private readonly cb: (list: LcpEntryList) => void) {}
      observe(): void {
        // buffered 首批：当前 LCP 候选（末条为当前最大）；随后又收到一次候选批次
        this.cb({ getEntries: () => [{ startTime: 100 }, { startTime: 123.5 }] });
        this.cb({ getEntries: () => [{ startTime: 123.5 }] });
      }
      disconnect(): void {
        disconnected += 1;
      }
    }
    const { client, metrics, reader } = makeFlushClient({
      win,
      performanceObserver: MockLcpObserver as unknown as LcpObserverCtor,
    });
    win.dispatch('load'); // LCP 已先上报 → load 兜底监听已移除，重复 load 无 data point
    await reader.forceFlush();

    const dps = collectMetric(metrics, METRIC_FIRST_SCREEN_LOAD);
    expect(dps).toHaveLength(1);
    expect(dps[0].value).toBe(123.5);
    expect(disconnected).toBe(1); // 上报后断开 observer（不再接收后续候选）
    expect(win.listenerCount('load')).toBe(0);
    await client.shutdown();
  });

  it('无 PerformanceObserver（显式 null）→ load 事件兜底，仍仅一个 data point', async () => {
    const win = makeFakeWindow();
    const { client, metrics, reader } = makeFlushClient({
      win,
      performanceObserver: null,
      loadTimeMs: () => 880,
    });
    expect(win.listenerCount('load')).toBe(1);
    win.dispatch('load');
    win.dispatch('load'); // 重复 load → 仍仅一个
    await reader.forceFlush();

    const dps = collectMetric(metrics, METRIC_FIRST_SCREEN_LOAD);
    expect(dps).toHaveLength(1);
    expect(dps[0].value).toBe(880);
    await client.shutdown();
  });
});

describe('T15 flush 触发（pagehide / visibilitychange=hidden）', () => {
  it('visibilitychange=hidden → trace/meter provider forceFlush 被调用；visible 不触发', async () => {
    const traceFlush = vi
      .spyOn(WebTracerProvider.prototype, 'forceFlush')
      .mockImplementation(() => Promise.resolve());
    const meterFlush = vi
      .spyOn(MeterProvider.prototype, 'forceFlush')
      .mockImplementation(() => Promise.resolve());
    const win = makeFakeWindow();
    const { client } = makeFlushClient({ win });

    win.dispatch('visibilitychange'); // visible → 不触发
    expect(traceFlush).not.toHaveBeenCalled();

    win.setVisibility('hidden');
    win.dispatch('visibilitychange');
    await vi.waitFor(() => expect(traceFlush).toHaveBeenCalled());
    expect(meterFlush).toHaveBeenCalled();
    await client.shutdown();
  });

  it('pagehide → forceFlush 被调用（含 visible 状态，Safari 兜底路径）', async () => {
    const traceFlush = vi
      .spyOn(WebTracerProvider.prototype, 'forceFlush')
      .mockImplementation(() => Promise.resolve());
    const win = makeFakeWindow();
    const { client } = makeFlushClient({ win });

    win.dispatch('pagehide');
    await vi.waitFor(() => expect(traceFlush).toHaveBeenCalled());
    await client.shutdown();
  });

  it('visibilitychange=hidden 与 pagehide 相继触发 → forceFlush 仅一次（去重，避免双份 beacon）', async () => {
    const traceFlush = vi
      .spyOn(WebTracerProvider.prototype, 'forceFlush')
      .mockImplementation(() => Promise.resolve());
    const win = makeFakeWindow();
    const { client } = makeFlushClient({ win });

    win.setVisibility('hidden');
    win.dispatch('visibilitychange');
    win.dispatch('pagehide'); // 同一卸载序列 → 去重
    await vi.waitFor(() => expect(traceFlush).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 20)); // 等 flush 完成、去重窗口释放
    expect(traceFlush).toHaveBeenCalledTimes(1);
    await client.shutdown();
  });

  it('default windowLike + 真实 DOM 形态（window 无 visibilityState 成员，只在 document 上）→ visibilitychange=hidden 触发 flush（Critical-1 回归）', async () => {
    // 真实浏览器：visibilityState 是 Document 属性、window 无该成员。旧实现按方法调用
    // win.visibilityState() → 生产默认路径（宿主不注入 windowLike）每次抛 TypeError，
    // visibilitychange flush 永不执行；FakeWindow 的方法形态恰好掩盖。此处以全局变量
    // 模拟真实 window/document 形态，钉死 defaultWindowLike() 适配器路径。
    const listeners = new Map<string, Set<() => void>>();
    const rawWindow = {
      addEventListener: (t: string, h: () => void) => {
        let set = listeners.get(t);
        if (!set) {
          set = new Set();
          listeners.set(t, set);
        }
        set.add(h);
      },
      removeEventListener: (t: string, h: () => void) => {
        listeners.get(t)?.delete(h);
      },
    };
    const doc = { visibilityState: 'hidden' };
    const winDesc = Object.getOwnPropertyDescriptor(globalThis, 'window');
    const docDesc = Object.getOwnPropertyDescriptor(globalThis, 'document');
    Object.defineProperty(globalThis, 'window', { configurable: true, value: rawWindow });
    Object.defineProperty(globalThis, 'document', { configurable: true, value: doc });
    try {
      const traceFlush = vi
        .spyOn(WebTracerProvider.prototype, 'forceFlush')
        .mockImplementation(() => Promise.resolve());
      const meterFlush = vi
        .spyOn(MeterProvider.prototype, 'forceFlush')
        .mockImplementation(() => Promise.resolve());
      const reader = new PeriodicExportingMetricReader({
        exporter: new InMemoryMetricExporter(AggregationTemporality.DELTA),
        exportIntervalMillis: LONG_DELAY_MS,
      });
      const client = new NlObserveClient();
      client.init({
        serviceName: 't15',
        customerId: 'C-1',
        batchTimeoutMs: LONG_DELAY_MS,
        metricReaders: [reader],
      }); // 不注入 windowLike → 走 defaultWindowLike() 适配器

      // 派发 visibilitychange：旧缺陷下此处抛 TypeError（win.visibilityState is not a function）
      for (const h of [...(listeners.get('visibilitychange') ?? [])]) h();
      await vi.waitFor(() => expect(traceFlush).toHaveBeenCalled());
      expect(meterFlush).toHaveBeenCalled();
      await client.shutdown();
    } finally {
      if (winDesc) {
        Object.defineProperty(globalThis, 'window', winDesc);
      } else {
        delete (globalThis as Record<string, unknown>).window;
      }
      if (docDesc) {
        Object.defineProperty(globalThis, 'document', docDesc);
      } else {
        delete (globalThis as Record<string, unknown>).document;
      }
    }
  });
});

describe('T15 nl.sdk.flush 上报（失败/超时/成功形态固化）', () => {
  it('forceFlush reject（死端点真实导出失败）→ nl.sdk.flush{flush:beacon, result:fail, dropped_spans:1}；二次失败仅 console.warn 不抛错', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const win = makeFakeWindow();
    const { client, metrics, reader } = makeFlushClient({
      otlpUrl: DEAD_OTLP_URL,
      flushTimeoutMs: 3_000,
      win,
    });
    client.signIn('C-1');
    const bizId = client.startBusiness('ORDER');
    expect(bizId).toBeDefined();
    client.endBusiness(bizId!, { status: 'success' }); // 1 个 span 进入 BSP 队列

    win.setVisibility('hidden');
    win.dispatch('visibilitychange');
    await vi.waitFor(
      () => {
        const dps = collectMetric(metrics, METRIC_SDK_FLUSH);
        expect(dps.length).toBe(1);
      },
      { timeout: 5_000 },
    );
    await reader.forceFlush(); // 双保险：读取器侧再收集一次

    const dps = collectMetric(metrics, METRIC_SDK_FLUSH);
    expect(dps.length).toBe(1);
    expect(dps[0].value).toBe(1);
    expect(dps[0].attributes[ATTR_FLUSH]).toBe(FLUSH_MODE_BEACON);
    expect(dps[0].attributes[ATTR_FLUSH_RESULT]).toBe('fail');
    expect(dps[0].attributes[ATTR_DROPPED_SPANS]).toBe(1);
    // 二次失败（flush 指标自身经 beacon 再导出也失败）→ 仅 console，不抛错
    expect(warn.mock.calls.some((c) => String(c[0]).includes('flush'))).toBe(true);
    await client.shutdown();
  });

  it('forceFlush 挂起 → result=timeout（flushTimeoutMs 可配，默认 2000ms）', async () => {
    expect(DEFAULT_FLUSH_TIMEOUT_MS).toBe(2000);
    vi.spyOn(WebTracerProvider.prototype, 'forceFlush').mockImplementation(
      () => new Promise<never>(() => {}),
    ); // 永不 settle
    const win = makeFakeWindow();
    const { client, metrics } = makeFlushClient({ win, flushTimeoutMs: 30 });

    win.setVisibility('hidden');
    win.dispatch('visibilitychange');
    await vi.waitFor(
      () => {
        const dps = collectMetric(metrics, METRIC_SDK_FLUSH);
        expect(dps.length).toBe(1);
      },
      { timeout: 5_000 },
    );
    const dp = collectMetric(metrics, METRIC_SDK_FLUSH)[0];
    expect(dp.attributes[ATTR_FLUSH_RESULT]).toBe('timeout');
    expect(dp.attributes[ATTR_DROPPED_SPANS]).toBe(0);
    await client.shutdown();
  });

  it('flush 成功 → nl.sdk.flush{result:ok, dropped_spans:0}', async () => {
    vi.spyOn(WebTracerProvider.prototype, 'forceFlush').mockImplementation(() =>
      Promise.resolve(),
    );
    const win = makeFakeWindow();
    const { client, metrics } = makeFlushClient({ win });

    win.setVisibility('hidden');
    win.dispatch('visibilitychange');
    await vi.waitFor(
      () => {
        const dps = collectMetric(metrics, METRIC_SDK_FLUSH);
        expect(dps.length).toBe(1);
      },
      { timeout: 5_000 },
    );
    const dp = collectMetric(metrics, METRIC_SDK_FLUSH)[0];
    expect(dp.value).toBe(1);
    expect(dp.attributes[ATTR_FLUSH]).toBe(FLUSH_MODE_BEACON);
    expect(dp.attributes[ATTR_FLUSH_RESULT]).toBe('ok');
    expect(dp.attributes[ATTR_DROPPED_SPANS]).toBe(0);
    await client.shutdown();
  });

  it('陈旧 lastError（本次 flush 前的周期导出失败）→ flushNow 入口先 reset，成功 flush 仍 result=ok（Major-1 回归）', async () => {
    let exportError: unknown = new Error('stale periodic export failure');
    const reported: Array<{ result: FlushResult; dropped: number }> = [];
    const hook = setupFlushHook({
      forceFlushAll: () => Promise.resolve(),
      getDroppedSpans: () => 0,
      reportFlush: (result, dropped) => reported.push({ result, dropped }),
      getExportError: () => exportError,
      resetExportError: () => {
        exportError = undefined;
      },
      windowLike: null, // 不挂监听，直接驱动 flushNow
    });

    await hook.flushNow();

    expect(reported).toHaveLength(1);
    expect(reported[0].result).toBe('ok'); // 修复前：陈旧错误驻留 → 成功 flush 误报 'fail'
    expect(reported[0].dropped).toBe(0);
    hook.dispose();
  });
});

describe('T15 sendBeacon transport（OTLP/HTTP JSON 文本口径）', () => {
  it('浏览器环境 → navigator.sendBeacon（Blob application/json，JSON 文本载荷）', async () => {
    const calls: Array<{ url: string; data: unknown }> = [];
    await withFakeNavigator(
      { sendBeacon: (url: string, data: unknown) => { calls.push({ url, data }); return true; } },
      async () => {
        const transport = createOtlpHttpBeaconTransport();
        await transport('http://h:4318/v1/traces', '{"x":1}');
        expect(calls).toHaveLength(1);
        expect(calls[0].url).toBe('http://h:4318/v1/traces');
        const blob = calls[0].data as Blob;
        expect(blob).toBeInstanceOf(Blob);
        expect(blob.type).toBe('application/json');
        expect(await blob.text()).toBe('{"x":1}');
      },
    );
  });

  it('sendBeacon 返回 false（配额/超限被拒）→ transport reject（→ flush fail 上报路径）', async () => {
    await withFakeNavigator({ sendBeacon: () => false }, async () => {
      const transport = createOtlpHttpBeaconTransport();
      await expect(transport('http://h:4318/v1/traces', '{}')).rejects.toThrow(/sendBeacon/);
    });
  });

  it('metricsUrlFromTracesUrl：/v1/traces → /v1/metrics；base URL 追加 /v1/metrics', () => {
    expect(metricsUrlFromTracesUrl('http://h:4318/v1/traces')).toBe('http://h:4318/v1/metrics');
    expect(metricsUrlFromTracesUrl('http://h:4318/v1/traces/')).toBe('http://h:4318/v1/metrics');
    expect(metricsUrlFromTracesUrl('http://h:4318')).toBe('http://h:4318/v1/metrics');
    // 安全 F-4：带 query 的 endpoint 按 URL 解析派生——query 保留、path 正确替换
    // （旧字符串拼接会产出坏 URL `…?key=K/v1/metrics` → metrics 通道静默永久失效）
    expect(metricsUrlFromTracesUrl('http://h:4318/v1/traces?key=K')).toBe('http://h:4318/v1/metrics?key=K');
    expect(metricsUrlFromTracesUrl('http://h:4318/base')).toBe('http://h:4318/base/v1/metrics');
    // 相对 URL（new URL 抛错）：保留字符串拼接语义
    expect(metricsUrlFromTracesUrl('/v1/traces')).toBe('/v1/metrics');
  });

  it('safeUrlForLog：剥离 query/userinfo/hash——失败日志不泄漏 URL 内嵌凭据（安全 F-3）', () => {
    expect(safeUrlForLog('https://user:pass@h:4318/v1/traces?k=K#frag')).toBe('https://h:4318/v1/traces');
    expect(safeUrlForLog('http://h:4318/v1/traces?key=K')).toBe('http://h:4318/v1/traces');
    expect(safeUrlForLog('/v1/traces?k=K#f')).toBe('/v1/traces');
  });
});

describe('T15 OTLP/JSON 载荷形态（SendBeacon 导出器）', () => {
  it('SendBeaconSpanExporter：OTLP/JSON 文本载荷，traceId/spanId 为 hex（与 T05 口径一致）', async () => {
    const bodies: string[] = [];
    const exporter = new SendBeaconSpanExporter({
      url: 'http://h:4318/v1/traces',
      transport: (_url, body) => {
        bodies.push(body);
        return Promise.resolve();
      },
    });
    const finished: ReadableSpan[] = [];
    const capture: SpanProcessor = {
      onStart: () => undefined,
      onEnd: (s) => finished.push(s),
      shutdown: () => Promise.resolve(),
      forceFlush: () => Promise.resolve(),
    };
    const provider = new WebTracerProvider({
      resource: resourceFromAttributes({ 'service.name': 't15' }),
      spanProcessors: [capture],
    });
    const span = provider.getTracer('t15').startSpan('biz.business.ORDER');
    span.end();
    await provider.shutdown();

    let result: ExportResult | undefined;
    await new Promise<void>((resolve) => {
      exporter.export(finished, (r) => {
        result = r;
        resolve();
      });
    });
    expect(result?.code).toBe(ExportResultCode.SUCCESS);
    expect(bodies).toHaveLength(1);
    const payload = JSON.parse(bodies[0]) as {
      resourceSpans: Array<{ scopeSpans: Array<{ spans: Array<{ name: string; traceId: string; spanId: string }> }> }>;
    };
    const s = payload.resourceSpans[0].scopeSpans[0].spans[0];
    expect(s.name).toBe('biz.business.ORDER');
    expect(s.traceId).toMatch(/^[0-9a-f]{32}$/);
    expect(s.spanId).toMatch(/^[0-9a-f]{16}$/);
  });

  it('SendBeaconMetricExporter：Counter 必须 DELTA（T13 锁定）；OTLP/JSON sum data point；DELTA 不跨周期重发', async () => {
    const bodies: string[] = [];
    const exporter = new SendBeaconMetricExporter({
      url: 'http://h:4318/v1/metrics',
      transport: (_url, body) => {
        bodies.push(body);
        return Promise.resolve();
      },
    });
    expect(exporter.selectAggregationTemporality(InstrumentType.COUNTER)).toBe(AggregationTemporality.DELTA);

    const reader = new PeriodicExportingMetricReader({ exporter, exportIntervalMillis: LONG_DELAY_MS });
    const mp = new MeterProvider({
      resource: resourceFromAttributes({ 'service.name': 't15' }),
      readers: [reader],
    });
    const counter = mp.getMeter('t15').createCounter('nl.test_counter');
    counter.add(5, { k: 'v' });
    await reader.forceFlush();
    expect(bodies).toHaveLength(1);
    const payload = JSON.parse(bodies[0]) as {
      resourceMetrics: Array<{
        scopeMetrics: Array<{ metrics: Array<{ sum: { dataPoints: Array<{ asInt?: string; asDouble?: number; attributes?: Array<{ key: string; value: unknown }> }> } }> }>;
      }>;
    };
    const dp = payload.resourceMetrics[0].scopeMetrics[0].metrics[0].sum.dataPoints[0];
    const value = typeof dp.asInt === 'string' ? Number(dp.asInt) : dp.asDouble;
    expect(value).toBe(5);
    expect(dp.attributes?.some((a) => a.key === 'k')).toBe(true);

    // DELTA：无新增的下一周期不重发（cumulative 会重发 → 后端 append 表重复行，T13 Major-1）
    await reader.forceFlush();
    expect(bodies).toHaveLength(1);
    await mp.shutdown();
  });

  it('SendBeaconMetricExporter：失败→成功后 lastError 清零（陈旧错误不驻留，Major-1 源头修复）', async () => {
    let failing = true;
    const exporter = new SendBeaconMetricExporter({
      url: 'http://h:4318/v1/metrics',
      transport: () =>
        failing ? Promise.reject(new Error('periodic export boom')) : Promise.resolve(),
    });
    const reader = new PeriodicExportingMetricReader({ exporter, exportIntervalMillis: LONG_DELAY_MS });
    const mp = new MeterProvider({
      resource: resourceFromAttributes({ 'service.name': 't15' }),
      readers: [reader],
    });
    const counter = mp.getMeter('t15').createCounter('nl.test_counter');

    counter.add(1, { k: 'v' });
    await reader.forceFlush(); // 导出失败（reader 吞错）→ lastError 置位
    expect(exporter.getLastError()).toBeDefined();

    failing = false;
    counter.add(1, { k: 'v' });
    await reader.forceFlush(); // 导出成功 → lastError 清零
    expect(exporter.getLastError()).toBeUndefined();
    await mp.shutdown();
  });
});

describe('T15 DropCountingSpanExporter（dropped_spans 计数）', () => {
  it('失败批次计入 dropped；在途批次计入；成功导出清零', async () => {
    let mode: 'fail' | 'ok' | 'hang' = 'fail';
    const inner: SpanExporter = {
      export: (_spans, cb) => {
        if (mode === 'fail') cb({ code: ExportResultCode.FAILED });
        else if (mode === 'ok') cb({ code: ExportResultCode.SUCCESS });
        // 'hang'：永不回调（模拟导出挂起）
      },
      shutdown: () => Promise.resolve(),
    };
    const wrapped = new DropCountingSpanExporter(inner);

    wrapped.export([fakeSpan(), fakeSpan()], () => undefined);
    expect(wrapped.getDropped()).toBe(2); // 失败批次 2 个 → dropped

    mode = 'hang';
    wrapped.export([fakeSpan()], () => undefined);
    expect(wrapped.getDropped()).toBe(3); // 已确认 2 + 在途 1（超时即随页面丢失）

    mode = 'ok';
    await new Promise<void>((resolve) => {
      wrapped.export([fakeSpan()], () => resolve());
    });
    expect(wrapped.getDropped()).toBe(0); // 成功导出 → 清零
  });
});

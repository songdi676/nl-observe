/**
 * T21 POC 对照组：无 micro-app（纯 jsdom 测试进程直接驱动 runSubApp）。
 *
 * 作用：
 * 1) 证明 POC 工装本身正确——两阶段流程（自动捕获 → 兜底补记）、span 结构、parent 钉住
 *    在无沙箱基线下必须 10/10 全捕获；沙箱场景捕获率若低于此即沙箱所致，非工装缺陷；
 * 2) 覆盖 src/sub-app.ts（bundle 入口只是胶水，见 app/bundle-entry.ts 注释）。
 */
import { afterEach, describe, expect, it } from 'vitest';
import { InMemorySpanExporter, SimpleSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { NlObserveClient } from '../../../sdk-web/src/core/client';
import { REST_SPAN_NAME_PREFIX } from '../../../sdk-web/src/core/rest/autoprofile';
import {
  installFetchBindingShim,
  startMockBackend,
  waitForEvent,
  type MockBackend,
} from '../src/harness';
import {
  getSubAppSdk,
  resetSubAppSdk,
  runSubApp,
  type SubAppPhaseDetail,
  type SubAppTrackRestDetail,
} from '../src/sub-app';

const FETCH_COUNT = 10;
const SPAN_KIND_CLIENT = 2; // @opentelemetry/api SpanKind.CLIENT

function restNames(spans: { name: string }[]): string[] {
  return spans.map((s) => s.name);
}

describe('T21 对照组（无 micro-app）：runSubApp 两阶段基线', () => {
  let backend: MockBackend | undefined;
  let hostClient: NlObserveClient | undefined;

  afterEach(async () => {
    const sdk = getSubAppSdk();
    if (sdk) {
      await sdk.client.shutdown();
      delete (globalThis as Record<string, unknown>).__NL_OBSERVE__;
    }
    resetSubAppSdk();
    await hostClient?.shutdown();
    hostClient = undefined;
    await backend?.stop();
    backend = undefined;
    document.body.innerHTML = '';
  });

  it('sub 模式（子应用自初始化 SDK）：自动 10/10 + 兜底 10 条 parent 全对', async () => {
    installFetchBindingShim();
    backend = await startMockBackend(() => '');
    const container = document.createElement('div');
    document.body.appendChild(container);

    runSubApp({ mode: 'sub', backendOrigin: backend.origin, container, fetchCount: FETCH_COUNT });
    expect((container as HTMLElement & { __POC_READY__?: boolean }).__POC_READY__).toBe(true);

    const phase1P = waitForEvent(container, 'poc:phase1');
    const doneP = waitForEvent(container, 'poc:done');
    container.dispatchEvent(new CustomEvent('poc:go'));

    const phase1 = (await phase1P).detail as SubAppPhaseDetail;
    expect(phase1.fetchErrors).toBe(0);
    expect(phase1.restSpans).toHaveLength(FETCH_COUNT); // 无沙箱基线：全捕获
    expect(restNames(phase1.restSpans!)).toEqual(Array(FETCH_COUNT).fill(`${REST_SPAN_NAME_PREFIX}GET`));

    container.dispatchEvent(new CustomEvent('poc:phase1-ack')); // 快照完成，放行阶段2
    const done = (await doneP).detail as SubAppPhaseDetail;
    const autoIds = new Set(phase1.restSpans!.map((s) => s.spanId));
    const fallback = (done.restSpans ?? []).filter((s) => !autoIds.has(s.spanId));
    expect(fallback).toHaveLength(FETCH_COUNT); // 兜底完整覆盖

    const rootSpanId = done.rootSpanId as string;
    const rootTraceId = done.rootTraceId as string;
    expect(rootSpanId).toBeTruthy();
    for (const s of [...phase1.restSpans!, ...fallback]) {
      expect(s.name).toBe(`${REST_SPAN_NAME_PREFIX}GET`);
      expect(s.kind).toBe(SPAN_KIND_CLIENT);
      expect(s.parentSpanId).toBe(rootSpanId);
      expect(s.traceId).toBe(rootTraceId);
    }
  }, 30_000);

  it('host 模式（宿主初始化 SDK，子应用不 init）：宿主包装 fetch 自动 10/10 + 兜底桥接 10 条', async () => {
    installFetchBindingShim();
    backend = await startMockBackend(() => '');
    const container = document.createElement('div');
    document.body.appendChild(container);

    const exporter = new InMemorySpanExporter();
    hostClient = new NlObserveClient();
    hostClient.init({
      serviceName: 'poc-control-host',
      customerId: 'C-21',
      spanProcessors: [new SimpleSpanProcessor(exporter)],
    });
    hostClient.signIn('C-21');
    hostClient.startBusiness('ORDER');
    const active = hostClient.activeBusiness();
    expect(active).toBeDefined();
    // 兜底桥接（与 harness.runCaptureScenario host 分支同构）
    container.addEventListener('poc:track-rest', ((ev: Event) => {
      const d = (ev as CustomEvent<SubAppTrackRestDetail>).detail;
      hostClient?.trackRest(d.method, d.route, d.statusCode, d.durationMs);
    }) as EventListener);

    runSubApp({ mode: 'host', backendOrigin: backend.origin, container, fetchCount: FETCH_COUNT });

    const phase1P = waitForEvent(container, 'poc:phase1');
    const doneP = waitForEvent(container, 'poc:done');
    container.dispatchEvent(new CustomEvent('poc:go'));

    const phase1 = (await phase1P).detail as SubAppPhaseDetail;
    expect(phase1.fetchErrors).toBe(0);
    const auto = exporter
      .getFinishedSpans()
      .filter((s) => s.name.startsWith(REST_SPAN_NAME_PREFIX));
    expect(auto).toHaveLength(FETCH_COUNT); // 宿主包装对子应用 fetch 全捕获（无沙箱）

    container.dispatchEvent(new CustomEvent('poc:phase1-ack')); // 快照完成，放行阶段2
    await doneP;
    const all = exporter
      .getFinishedSpans()
      .filter((s) => s.name.startsWith(REST_SPAN_NAME_PREFIX));
    expect(all).toHaveLength(FETCH_COUNT * 2); // 自动 + 兜底

    const rootSpanId = active!.span.spanContext().spanId;
    for (const s of all) {
      expect(s.parentSpanContext?.spanId).toBe(rootSpanId);
    }
  }, 30_000);
});

/**
 * T17 vue2-host 端到端冒烟（验收 3：vue2.vue3 双宿主）：
 * init→签入→v-biz→fetch→end → InMemory exporter 收到 root span + REST span（同 traceId、
 * parent 钉住业务 root）+ nl.business_duration 指标 data point；OTLP endpoint 不可达 →
 * 静默降级（console.debug），业务办理不受影响。
 * 与 vue3-host/e2e.spec 同构（共享 core 的双版本一致性口径，T16 同规则）。
 */
import { it, expect, vi, afterEach } from 'vitest';
import Vue, { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { InMemorySpanExporter, SimpleSpanProcessor, type ReadableSpan } from '@opentelemetry/sdk-trace-base';
import {
  AggregationTemporality,
  InMemoryMetricExporter,
  PeriodicExportingMetricReader,
} from '@opentelemetry/sdk-metrics';
import { createClient, registerVue2Directives } from '../../src/vue2';

const OTLP_TRACES = 'http://127.0.0.1:4318/v1/traces';

function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

afterEach(() => {
  delete (globalThis as Record<string, unknown>).__NL_OBSERVE__;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it('T17 e2e（vue2）：init→signIn→v-biz→fetch→end → root span + REST span；OTLP 不可达静默降级', async () => {
  const otlpCalls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
      const url = typeof input === 'string' ? input : String(input);
      if (url.includes('127.0.0.1:4318')) {
        otlpCalls.push(url);
        throw new TypeError('fetch failed: ECONNREFUSED'); // endpoint 不可达
      }
      return { status: 200 } as Response; // 业务 API 正常响应
    }),
  );
  const debug = vi.spyOn(console, 'debug').mockImplementation(() => {});

  const spanExporter = new InMemorySpanExporter();
  const metricExporter = new InMemoryMetricExporter(AggregationTemporality.CUMULATIVE);
  const client = createClient({
    serviceName: 'vue2-e2e',
    customerId: 'C-E2E',
    endpoint: OTLP_TRACES,
    batchTimeoutMs: 60000,
    spanProcessors: [new SimpleSpanProcessor(spanExporter)],
    metricReaders: [
      new PeriodicExportingMetricReader({ exporter: metricExporter, exportIntervalMillis: 60000 }),
    ],
  });
  client.signIn('C-E2E');

  // 与 vue2 宿主既有口径一致：真实 Vue 全局注册 + vnode directives 数据（vue@2.7 无 withDirectives）
  registerVue2Directives(Vue, client);

  const Comp = defineComponent({
    name: 'BizHost',
    setup() {
      return () => h('div', { directives: [{ name: 'biz', value: 'ORDER' }] }, 'ok');
    },
  });
  const wrapper = mount(Comp);
  await settle();

  const res = await fetch('http://svc.local/api/orders?token=secret', { method: 'POST' });
  expect(res.status).toBe(200);
  client.endBusiness('ORDER', { status: 'success' });
  await settle();

  const finished = spanExporter.getFinishedSpans();
  const root = finished.find((s: ReadableSpan) => s.name === 'biz.business.ORDER');
  const rest = finished.find((s: ReadableSpan) => s.name === 'HTTP POST');
  expect(root).toBeDefined();
  expect(rest).toBeDefined();
  expect(rest!.spanContext().traceId).toBe(root!.spanContext().traceId);
  expect(rest!.parentSpanContext?.spanId).toBe(root!.spanContext().spanId);
  expect(rest!.attributes['http.route']).toBe('/api/orders');
  expect(rest!.attributes['http.response.status_code']).toBe(200);
  expect(root!.attributes['biz.status']).toBe('success');

  // ROOT_CONTEXT 为父：不同业务 traceId 不同
  const biz2 = client.startBusiness('PAY')!;
  client.endBusiness(biz2, { status: 'success' });
  await settle();
  const roots = finished
    .concat(spanExporter.getFinishedSpans())
    .filter((s: ReadableSpan) => s.name.startsWith('biz.business.'));
  expect(new Set(roots.map((s) => s.spanContext().traceId)).size).toBe(2);

  wrapper.destroy(); // VTU v1（vue2 宿主）无 unmount，destroy 即卸载（触发 unbind 钩子）

  // shutdown 冲刷：metric → InMemoryMetricExporter；OTLP → 不可达降级
  await client.shutdown();

  // 指标 data point（与 vue3 宿主同口径：nl.business_duration，属性含 biz.code/status/trace_id）
  const metrics = metricExporter
    .getMetrics()
    .flatMap((rsm) => rsm.scopeMetrics.flatMap((sm) => sm.metrics));
  const bizDuration = metrics.find((m) => m.descriptor.name === 'nl.business_duration');
  expect(bizDuration).toBeDefined();
  const orderDp = bizDuration!.dataPoints.find((dp) => dp.attributes['biz.code'] === 'ORDER');
  expect(orderDp).toBeDefined();
  expect(orderDp!.attributes['biz.status']).toBe('success');
  expect(orderDp!.attributes['biz.trace_id']).toBe(root!.spanContext().traceId);

  expect(otlpCalls.length).toBeGreaterThan(0);
  expect(
    debug.mock.calls.some((a) => String(a[0]).includes('[nl-observe]') && String(a[0]).includes('degraded')),
  ).toBe(true);
});

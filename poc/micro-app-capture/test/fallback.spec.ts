/**
 * T21 单测（test_cases 2）：兜底路径 span 结构断言——trackRest 手动 REST 兜底 +
 * v-span 手动子 span 兜底（风险 #5：自动埋点捕获不足时的完整覆盖路径）。
 *
 * 断言口径（与 T14 自动埋点共享 recordRestSpan，行为一致）：
 * - trackRest：name='HTTP {METHOD}'、SpanKind=CLIENT(1)、parent=当前 ACTIVE 业务 root
 *   span（同 traceId + parentSpanId=root spanId）、属性恰为白名单 4 项、route 去 query；
 *   无 ACTIVE 业务 → 不上报（与自动埋点同口径）；未 init 门面 no-op。
 * - v-span：子 span parent=ACTIVE 业务 root（同 traceId、parentSpanId 钉住），
 *   unmount 结束——兜底 span parent 正确的第二形态。
 * 本单测在沙箱外验证结构；沙箱内兜底有效性由 capture-*.spec.ts 的场景阶段2 实测。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, withDirectives } from 'vue';
import { mount } from '@vue/test-utils';
import {
  InMemorySpanExporter,
  SimpleSpanProcessor,
  type ReadableSpan,
} from '@opentelemetry/sdk-trace-base';
import {
  NlObserveClient,
  registerVue3Directives,
  type Vue3DirectiveDef,
} from '../../../sdk-web/src/vue3';
import {
  ATTR_HTTP_REQUEST_METHOD,
  ATTR_HTTP_RESPONSE_STATUS_CODE,
  ATTR_HTTP_ROUTE,
  ATTR_NL_DURATION_MS,
  REST_SPAN_NAME_PREFIX,
} from '../../../sdk-web/src/core/rest/autoprofile';

/** REST span 属性白名单 4 项（与 backend AttributeKeys.REST_SPAN_WHITELIST 一致）。 */
const REST_ATTR_KEYS_SORTED = [
  ATTR_HTTP_REQUEST_METHOD,
  ATTR_HTTP_RESPONSE_STATUS_CODE,
  ATTR_HTTP_ROUTE,
  ATTR_NL_DURATION_MS,
].sort();

const SPAN_KIND_CLIENT = 2; // @opentelemetry/api SpanKind 枚举：INTERNAL=0/SERVER=1/CLIENT=2

interface Harness {
  client: NlObserveClient;
  exporter: InMemorySpanExporter;
}

function makeClient(clock?: () => number): Harness {
  const exporter = new InMemorySpanExporter();
  const client = new NlObserveClient();
  client.init({
    serviceName: 'poc-fallback',
    customerId: 'C-21',
    clock,
    spanProcessors: [new SimpleSpanProcessor(exporter)],
  });
  client.signIn('C-21');
  return { client, exporter };
}

function restSpans(exporter: InMemorySpanExporter): ReadableSpan[] {
  return exporter.getFinishedSpans().filter((s) => s.name.startsWith(REST_SPAN_NAME_PREFIX));
}

/** v-span 指令定义捕获（与真实 app.directive 注册同构）。 */
function captureSpanDirective(client: NlObserveClient): Vue3DirectiveDef {
  const defs: Record<string, Vue3DirectiveDef> = {};
  registerVue3Directives({ directive: (name, def) => void (defs[name] = def) }, client);
  return defs.span;
}

afterEach(() => {
  delete (globalThis as Record<string, unknown>).__NL_OBSERVE__;
  vi.restoreAllMocks();
});

describe('T21 兜底：trackRest 手动 REST span 结构', () => {
  it('ACTIVE 业务下 trackRest 产生 REST span：命名/CLIENT/parent=业务 root/白名单 4 属性', () => {
    const { client, exporter } = makeClient();
    const rootId = client.startBusiness('ORDER') as string;
    expect(rootId).toBeTruthy();
    // 业务 root span 存活（未 end）→ 经 client 活 span 取 spanId 作 parent 基准
    const active = client.activeBusiness();
    expect(active).toBeDefined();

    client.trackRest('get', '/api/order?token=***', 200, 42);

    const spans = restSpans(exporter);
    expect(spans).toHaveLength(1);
    const s = spans[0];
    expect(s.name).toBe('HTTP GET'); // 方法大写
    expect(s.kind).toBe(SPAN_KIND_CLIENT);
    // parent 正确：同 traceId + parentSpanId = 业务 root spanId
    expect(s.spanContext().traceId).toBe(active!.span.spanContext().traceId);
    expect(s.parentSpanContext?.spanId).toBe(active!.span.spanContext().spanId);
    // 属性恰为白名单 4 项，query 绝不入属性
    expect(Object.keys(s.attributes).sort()).toEqual(REST_ATTR_KEYS_SORTED);
    expect(s.attributes[ATTR_HTTP_REQUEST_METHOD]).toBe('GET');
    expect(s.attributes[ATTR_HTTP_ROUTE]).toBe('/api/order'); // 去 query
    expect(s.attributes[ATTR_HTTP_RESPONSE_STATUS_CODE]).toBe(200);
    expect(s.attributes[ATTR_NL_DURATION_MS]).toBe(42);
  });

  it('无 ACTIVE 业务（未起业务）→ trackRest 不上报（与自动埋点同口径）', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { client, exporter } = makeClient();
    client.startBusiness('ORDER');
    client.endBusiness('ORDER', { status: 'success' });
    client.trackRest('GET', '/api/x', 200, 5);
    expect(restSpans(exporter)).toHaveLength(0);
    expect(warn).not.toHaveBeenCalled();
  });

  it('未签入 → startBusiness 拒绝，trackRest 无 ACTIVE 不上报', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { client, exporter } = makeClient();
    client.signOut();
    expect(client.startBusiness('ORDER')).toBeUndefined();
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('startBusiness called while signed out'));
    client.trackRest('GET', '/api/x', 200, 5);
    expect(restSpans(exporter)).toHaveLength(0);
  });

  it('trackRest 注入时钟：duration 负值归零不抛（同 T14 口径）', () => {
    const { client, exporter } = makeClient();
    client.startBusiness('ORDER');
    client.trackRest('POST', '/api/pay', 500, -3);
    const spans = restSpans(exporter);
    expect(spans).toHaveLength(1);
    expect(spans[0].attributes[ATTR_NL_DURATION_MS]).toBe(0);
    expect(spans[0].attributes[ATTR_HTTP_RESPONSE_STATUS_CODE]).toBe(500);
  });

  it('未 init 的 client.trackRest no-op 不抛', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const client = new NlObserveClient();
    client.trackRest('GET', '/api/x', 200, 1);
    expect(client.isInitialized).toBe(false);
    expect(warn.mock.calls.some((c) => String(c[0]).includes('trackRest'))).toBe(true);
  });
});

describe('T21 兜底：v-span 手动子 span parent 正确', () => {
  it('v-span 子 span parent=当前 ACTIVE 业务 root（同 traceId、parentSpanId 钉住）', async () => {
    const { client, exporter } = makeClient();
    const defs: Record<string, Vue3DirectiveDef> = {};
    registerVue3Directives({ directive: (name, def) => void (defs[name] = def) }, client);
    const spanDef = captureSpanDirective(client);
    void defs;

    const Comp = defineComponent({
      name: 'FallbackSpan',
      render() {
        return withDirectives(h('div', [withDirectives(h('span', 'step'), [[spanDef, 'STEP1']])]), [
          [defs.biz, 'ORDER'],
        ]);
      },
    });
    const wrapper = mount(Comp);
    await new Promise((r) => setTimeout(r, 0)); // post-render effect 冲刷（v-span 让出微任务）
    wrapper.unmount();

    const root = exporter.getFinishedSpans().find((s) => s.name === 'biz.business.ORDER');
    const child = exporter.getFinishedSpans().find((s) => s.name === 'nl.vspan.STEP1');
    expect(root).toBeDefined();
    expect(child).toBeDefined();
    expect(child!.spanContext().traceId).toBe(root!.spanContext().traceId);
    expect(child!.parentSpanContext?.spanId).toBe(root!.spanContext().spanId);
    expect(root!.attributes['biz.status']).toBe('fail'); // unmount 兜底 end（未显式 end）
  });

  it('无 ACTIVE 业务时 v-span warn+skip（不产生子 span）', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { client, exporter } = makeClient();
    const spanDef = captureSpanDirective(client);

    const Comp = defineComponent({
      name: 'FallbackSpanLoose',
      render() {
        return withDirectives(h('span', 'step'), [[spanDef, 'LOOSE']]);
      },
    });
    const wrapper = mount(Comp);
    await new Promise((r) => setTimeout(r, 0));
    expect(warn.mock.calls.some((c) => String(c[0]).includes('v-span'))).toBe(true);
    expect(exporter.getFinishedSpans()).toHaveLength(0);
    wrapper.unmount();
    expect(exporter.getFinishedSpans()).toHaveLength(0);
  });
});

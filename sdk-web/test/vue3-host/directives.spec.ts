/**
 * T16 单测（vue3 宿主，@vue/test-utils v2）：v-biz / v-span 指令生命周期 + 单例守卫
 *（任务 test_cases 2/3/4，与 vue2 宿主行为对齐——同一共享 lifecycle）。
 *
 * - v-biz：组件挂载（app.directive created→mounted 钩子）起业务 root span，
 *   unmount 未显式结束 → end_reason=unmount、status=fail；已显式 end 则 unmount 不重复结束。
 * - v-span：挂载起子 span（parent=当前 ACTIVE 业务 root span，同 traceId、parentSpanId 钉住），
 *   unmount 结束；无 ACTIVE 业务 → warn+skip 不产生子 span。
 * - 单例守卫：二次 createClient 返回同一实例并 warn，window.__NL_OBSERVE__ 钉住 {version, instance}。
 *
 * 钩子时序注：vue3 的 mounted 指令钩子经 post-render effects 冲刷（子元素先于父元素入队），
 * v-span 的启动让出微任务后才能看到同 tick 内 v-biz 起的业务——断言前先 settle（宏任务冲刷）。
 * 指令经 registerVue3Directives 捕获真实 Vue3DirectiveDef，宿主 mount 时走真实钩子链。
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { defineComponent, h, withDirectives } from 'vue';
import { mount } from '@vue/test-utils';
import { InMemorySpanExporter, SimpleSpanProcessor, type ReadableSpan } from '@opentelemetry/sdk-trace-base';
import {
  NlObserveClient,
  createClient,
  registerVue3Directives,
  type Vue3DirectiveDef,
} from '../../src/vue3';

/** 冲刷微任务队列（宏任务边界保证 post-render effects + queueMicrotask 链全部落地）。 */
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function makeClient(): { client: NlObserveClient; exporter: InMemorySpanExporter } {
  const exporter = new InMemorySpanExporter();
  const client = new NlObserveClient();
  client.init({
    serviceName: 'sdk-web-vue3-directives',
    customerId: 'C-36',
    spanProcessors: [new SimpleSpanProcessor(exporter)],
  });
  client.signIn('C-36');
  return { client, exporter };
}

/** 经 registerVue3Directives 捕获真实指令定义（与 app.directive 注册同构）。 */
function captureDirectives(client: NlObserveClient): { biz: Vue3DirectiveDef; span: Vue3DirectiveDef } {
  const defs: Record<string, Vue3DirectiveDef> = {};
  registerVue3Directives(
    {
      directive: (name, def) => {
        defs[name] = def;
      },
    },
    client,
  );
  return defs as { biz: Vue3DirectiveDef; span: Vue3DirectiveDef };
}

function finishedNames(exporter: InMemorySpanExporter): string[] {
  return exporter.getFinishedSpans().map((s: ReadableSpan) => s.name);
}

afterEach(() => {
  delete (globalThis as Record<string, unknown>).__NL_OBSERVE__;
  vi.restoreAllMocks();
});

describe('T16 vue3-host：v-biz 指令生命周期', () => {
  it('组件挂载 → 业务 root span 已起未止；unmount（未显式 end）→ end_reason=unmount、status=fail', async () => {
    const { client, exporter } = makeClient();
    const defs = captureDirectives(client);

    const Comp = defineComponent({
      name: 'BizHost',
      render() {
        return withDirectives(h('div', 'ok'), [[defs.biz, 'ORDER']]);
      },
    });
    const wrapper = mount(Comp);
    expect(finishedNames(exporter)).not.toContain('biz.business.ORDER'); // 存活期 span 未结束

    wrapper.unmount();
    const span = exporter.getFinishedSpans().find((s) => s.name === 'biz.business.ORDER');
    expect(span).toBeDefined();
    expect(span!.attributes['biz.end_reason']).toBe('unmount');
    expect(span!.attributes['biz.status']).toBe('fail');
    expect(span!.attributes['biz.code']).toBe('ORDER');
    expect(span!.spanContext().traceId).toMatch(/^[0-9a-f]{32}$/);
  });

  it('v-biz unmount 前已显式 end(success/explicit) → unmount 不重复结束，explicit 口径保留', async () => {
    const { client, exporter } = makeClient();
    const defs = captureDirectives(client);

    const Comp = defineComponent({
      name: 'BizHost',
      render() {
        return withDirectives(h('div', 'ok'), [[defs.biz, 'ORDER']]);
      },
    });
    const wrapper = mount(Comp);
    client.endBusiness('ORDER', { status: 'success' }); // 显式结束（按 bizCode 命中）
    wrapper.unmount();

    const ended = exporter.getFinishedSpans().filter((s) => s.name === 'biz.business.ORDER');
    expect(ended).toHaveLength(1); // 未二次 end
    expect(ended[0].attributes['biz.status']).toBe('success');
    expect(ended[0].attributes['biz.end_reason']).toBe('explicit');
  });
});

describe('T16 vue3-host：v-span 子 span 生命周期（test_case 3）', () => {
  it('v-span 子 span parent = 当前 ACTIVE 业务（同 traceId、parentSpanId=业务 root spanId）；unmount 结束', async () => {
    const { client, exporter } = makeClient();
    const defs = captureDirectives(client);

    const Comp = defineComponent({
      name: 'SpanHost',
      render() {
        return withDirectives(
          h('div', [withDirectives(h('span', 'step'), [[defs.span, 'STEP1']])]),
          [[defs.biz, 'ORDER']],
        );
      },
    });
    const wrapper = mount(Comp);
    await settle(); // v-span 让出微任务后以业务为父起 span
    wrapper.unmount();

    const root = exporter.getFinishedSpans().find((s) => s.name === 'biz.business.ORDER');
    const child = exporter.getFinishedSpans().find((s) => s.name === 'nl.vspan.STEP1');
    expect(root).toBeDefined();
    expect(child).toBeDefined();
    // OTel JS 2.x ReadableSpan 经 parentSpanContext 暴露父（无 parentSpanId 扁平属性）
    expect(child!.parentSpanContext?.spanId).toBe(root!.spanContext().spanId);
    expect(child!.spanContext().traceId).toBe(root!.spanContext().traceId);
    expect(child!.attributes['nl.customer_id']).toBe('C-36');
  });

  it('v-span name 违反约束（高基数/非法字符）→ warn+skip：不产生子 span（与 bizCode 同口径）', async () => {
    const { client, exporter } = makeClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const defs = captureDirectives(client);

    const Comp = defineComponent({
      name: 'BadSpan',
      render() {
        return withDirectives(h('div', 'ok'), [[defs.span, 'bad name!']]);
      },
    });
    const wrapper = mount(Comp);
    await settle();
    expect(
      warn.mock.calls.some(
        (c) => String(c[0]).includes('v-span') && String(c[0]).includes('must match'),
      ),
    ).toBe(true);
    expect(exporter.getFinishedSpans()).toHaveLength(0);

    wrapper.unmount();
    expect(exporter.getFinishedSpans()).toHaveLength(0);
  });

  it('v-biz 无 binding value（undefined）→ 归一为空串：startBusiness warn+no-op，挂载/unmount 不抛', async () => {
    const { client, exporter } = makeClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const defs = captureDirectives(client);

    const Comp = defineComponent({
      name: 'BizNoValue',
      render() {
        return withDirectives(h('div', 'ok'), [[defs.biz]]);
      },
    });
    const wrapper = mount(Comp);
    await settle();
    expect(warn.mock.calls.some((c) => String(c[0]).includes('startBusiness'))).toBe(true);
    expect(exporter.getFinishedSpans()).toHaveLength(0);

    wrapper.unmount();
    expect(exporter.getFinishedSpans()).toHaveLength(0); // no-op 的业务无需兜底 end
  });

  it('无 ACTIVE 业务时 v-span warn+skip：不产生子 span，挂载/unmount 不抛', async () => {
    const { client, exporter } = makeClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const defs = captureDirectives(client);

    const Comp = defineComponent({
      name: 'SpanOnly',
      render() {
        return withDirectives(h('div', 'ok'), [[defs.span, 'LOOSE']]);
      },
    });
    const wrapper = mount(Comp);
    await settle();
    expect(warn.mock.calls.some((c) => String(c[0]).includes('v-span'))).toBe(true);
    expect(exporter.getFinishedSpans()).toHaveLength(0);
    expect(finishedNames(exporter)).not.toContain('nl.vspan.LOOSE');

    wrapper.unmount();
    expect(exporter.getFinishedSpans()).toHaveLength(0); // skip 的 span 卸载也无需 end
  });
});

describe('T16 vue3-host：单例守卫（test_case 4）', () => {
  it('二次 init（createClient）返回同一实例并 warn；__NL_OBSERVE__ 钉住 version+instance', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const a = createClient({ serviceName: 'guard-a', customerId: 'C-1' });
    const b = createClient();
    expect(a).toBe(b);
    expect(warn.mock.calls.some((c) => String(c[0]).includes('single-init guard'))).toBe(true);

    const guard = (
      globalThis as unknown as Record<string, { version: string; instance: unknown }>
    )['__NL_OBSERVE__'];
    expect(guard).toBeDefined();
    expect(guard.instance).toBe(a);
    expect(typeof guard.version).toBe('string');
  });
});

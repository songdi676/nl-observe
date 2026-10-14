/**
 * T01 测试用例：poc/sdk-vue3-init — Vue3 宿主 InMemorySpanExporter 断言 span 导出。
 * 对应验收：@opentelemetry sdk-web(替代 sdk-trace-web) 及 otlp-http exporter 在 vue3 宿主可初始化并导出 span。
 */
import { describe, it, expect, afterEach } from 'vitest';
import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { trace, context } from '@opentelemetry/api';
import { initPocTracing, startBusinessRootSpan } from '../src/init';

let shutdown: (() => Promise<void>) | undefined;

afterEach(async () => {
  await shutdown?.();
  shutdown = undefined;
});

describe('vue3 宿主 OTel 初始化与 span 导出', () => {
  it('WebTracerProvider 初始化后，root span 经 InMemorySpanExporter 导出', async () => {
    const { provider, tracer, memoryExporter } = initPocTracing({
      serviceName: 'poc-vue3',
      customerId: 'C-001',
    });
    shutdown = () => provider.shutdown();

    const span = startBusinessRootSpan(tracer, 'ORDER');
    span.setAttribute('biz.code', 'ORDER');
    span.end();

    const finished = memoryExporter.getFinishedSpans();
    expect(finished).toHaveLength(1);
    expect(finished[0].name).toBe('biz.business.ORDER');
    expect(finished[0].attributes['biz.code']).toBe('ORDER');
    // resource 携带 nl.customer_id（详设 §7 客户标识约定）
    expect(finished[0].resource.attributes['nl.customer_id']).toBe('C-001');
    expect(finished[0].spanContext().traceId).toMatch(/^[0-9a-f]{32}$/);
  });

  it('两个业务 root span 的 traceId 不同（ROOT_CONTEXT 语义）', async () => {
    const { provider, tracer, memoryExporter } = initPocTracing({
      serviceName: 'poc-vue3',
      customerId: 'C-001',
    });
    shutdown = () => provider.shutdown();

    const a = startBusinessRootSpan(tracer, 'ORDER');
    const b = startBusinessRootSpan(tracer, 'SHIPPING');
    a.end();
    b.end();

    const [sa, sb] = memoryExporter.getFinishedSpans();
    expect(sa.spanContext().traceId).not.toBe(sb.spanContext().traceId);
  });

  it('Vue3 组件渲染期创建 REST 子 span，parent 归属业务 root span', async () => {
    const { provider, tracer, memoryExporter } = initPocTracing({
      serviceName: 'poc-vue3',
      customerId: 'C-002',
    });
    shutdown = () => provider.shutdown();

    const root = startBusinessRootSpan(tracer, 'ORDER');
    const rootCtx = trace.setSpan(context.active(), root);

    const Comp = defineComponent({
      name: 'BizHost',
      setup() {
        return () => {
          // 模拟组件渲染期一次 REST 子 span（parent=业务 root，详设 §7）
          const child = tracer.startSpan('HTTP GET', {
            attributes: { 'http.request.method': 'GET' },
          }, rootCtx);
          child.end();
          return h('div', 'ok');
        };
      },
    });

    const wrapper = mount(Comp);
    expect(wrapper.text()).toBe('ok');
    root.end();

    const spans = memoryExporter.getFinishedSpans();
    const child = spans.find((s) => s.name === 'HTTP GET');
    expect(child).toBeDefined();
    // OTel JS 2.x：parentSpanId 改为 parentSpanContext（POC 发现，见 poc-notes.md）
    expect(child!.parentSpanContext?.spanId).toBe(root.spanContext().spanId);
    expect(child!.spanContext().traceId).toBe(root.spanContext().traceId);
  });

  it('OTLPTraceExporter 可构造（otlp-http exporter 版本锁定验证）', async () => {
    const { provider, otlpExporter } = initPocTracing({
      serviceName: 'poc-vue3',
      customerId: 'C-001',
      otlpUrl: 'http://localhost:4318/v1/traces',
    });
    shutdown = () => provider.shutdown();
    expect(otlpExporter).toBeDefined();
    expect(typeof otlpExporter!.export).toBe('function');
  });
});

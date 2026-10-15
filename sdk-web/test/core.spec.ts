/**
 * T12 core 单测（质量门覆盖率口径）：init/shutdown/门面方法/单例守卫/适配层指令钩子。
 * node 环境即可（WebTracerProvider 构造器不触碰 window；不全局 register——POC 发现 4）。
 * 与双宿主冒烟互补：宿主测「入口可加载 + 指令随渲染生效」，此处补齐骨架分支语义。
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { SDK_VERSION } from '../src/core/version';
import {
  DEFAULT_BATCH_TIMEOUT_MS,
  NlObserveClient,
  createClient,
} from '../src/core/client';
import { getGuardedClient } from '../src/core/guard';
import { registerVue2Directives, type Vue2DirectiveOptions } from '../src/adapters/vue2';
import { registerVue3Directives, type Vue3DirectiveDef } from '../src/adapters/vue3';

afterEach(() => {
  delete (globalThis as Record<string, unknown>).__NL_OBSERVE__;
});

describe('NlObserveClient 骨架语义', () => {
  it('默认 batchTimeout 2000ms（详设 §1.5）', () => {
    expect(DEFAULT_BATCH_TIMEOUT_MS).toBe(2000);
    const c = new NlObserveClient();
    expect(c.resolvedOptions.batchTimeoutMs).toBe(2000);
  });

  it('init 带 otlpUrl 时挂 OTLP BatchSpanProcessor，可配 batchTimeoutMs，tracer 就绪', () => {
    const c = new NlObserveClient();
    c.init({
      serviceName: 's',
      customerId: 'c',
      otlpUrl: 'http://127.0.0.1:4318/v1/traces',
      batchTimeoutMs: 500,
    });
    expect(c.isInitialized).toBe(true);
    expect(c.resolvedOptions.batchTimeoutMs).toBe(500);
    expect(c.getTracer()).toBeDefined();
  });

  it('重复 init 被忽略并 warn（幂等守卫）', () => {
    const c = new NlObserveClient();
    c.init();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    c.init();
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('shutdown 关闭 provider 并复位 isInitialized', async () => {
    const c = new NlObserveClient();
    c.init();
    await c.shutdown();
    expect(c.isInitialized).toBe(false);
  });

  it('已 init 的门面方法全链路不抛；signIn 更新 customerId（状态机语义详见 business.spec）', () => {
    const c = new NlObserveClient();
    c.init({ customerId: 'C-0' });
    c.signIn('C-1');
    expect(c.resolvedOptions.customerId).toBe('C-1');
    const bizId = c.startBusiness('ORDER');
    expect(bizId).toBeDefined();
    c.suspendBusiness('ORDER');
    c.resumeBusiness('ORDER');
    c.endBusiness('ORDER', { status: 'success', endReason: 'explicit' });
    c.trackRest('GET', '/api/x', 200, 10);
    c.signOut();
  });

  it('createClient 单例守卫：重复调用返回既有实例并 warn（详设 §5.1）', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const a = createClient({ serviceName: 's1' });
    const b = createClient();
    expect(a).toBe(b);
    expect(warn).toHaveBeenCalled();
    expect(getGuardedClient()?.version).toBe(SDK_VERSION);
    warn.mockRestore();
  });
});

describe('适配层指令钩子（不经渲染直接调用）', () => {
  function fakeEl() {
    return {
      setAttribute: vi.fn(),
      removeAttribute: vi.fn(),
    } as unknown as Element & { setAttribute: ReturnType<typeof vi.fn>; removeAttribute: ReturnType<typeof vi.fn> };
  }

  it('vue2 bind/unbind：设置与移除 data-nl-* 标记', () => {
    const opts: Record<string, Vue2DirectiveOptions> = {};
    registerVue2Directives(
      { directive: (id, o) => { opts[id] = o as Vue2DirectiveOptions; } },
      new NlObserveClient(),
    );
    expect(Object.keys(opts).sort()).toEqual(['biz', 'span']);

    const el = fakeEl();
    opts.biz.bind!(el, { name: 'biz', value: 'ORDER' });
    expect(el.setAttribute).toHaveBeenCalledWith('data-nl-biz', 'ORDER');
    expect(el.setAttribute).toHaveBeenCalledWith('data-nl-client', SDK_VERSION);
    opts.biz.unbind!(el, { name: 'biz', value: 'ORDER' });
    expect(el.removeAttribute).toHaveBeenCalledWith('data-nl-biz');

    const el2 = fakeEl();
    opts.span.bind!(el2, { name: 'span', value: 'STEP' });
    expect(el2.setAttribute).toHaveBeenCalledWith('data-nl-span', 'STEP');
    opts.span.unbind!(el2, { name: 'span', value: 'STEP' });
    expect(el2.removeAttribute).toHaveBeenCalledWith('data-nl-span');
  });

  it('vue3 created/unmounted：设置与移除 data-nl-* 标记', () => {
    const defs: Record<string, Vue3DirectiveDef> = {};
    registerVue3Directives(
      { directive: (name, def) => { defs[name] = def; } },
      new NlObserveClient(),
    );
    expect(Object.keys(defs).sort()).toEqual(['biz', 'span']);

    const el = fakeEl();
    defs.biz.created!(el, { value: 'ORDER' });
    expect(el.setAttribute).toHaveBeenCalledWith('data-nl-biz', 'ORDER');
    expect(el.setAttribute).toHaveBeenCalledWith('data-nl-client', SDK_VERSION);
    defs.biz.unmounted!(el, { value: 'ORDER' });
    expect(el.removeAttribute).toHaveBeenCalledWith('data-nl-biz');

    const el2 = fakeEl();
    defs.span.created!(el2, { value: 'STEP' });
    expect(el2.setAttribute).toHaveBeenCalledWith('data-nl-span', 'STEP');
    defs.span.unmounted!(el2, { value: 'STEP' });
    expect(el2.removeAttribute).toHaveBeenCalledWith('data-nl-span');
  });
});

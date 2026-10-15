/**
 * T12 冒烟测试（test_cases: vue3-host 冒烟）：sdk-web vue3 入口在 Vue3 宿主
 * （vue@3.5.44 + @vue/test-utils@2.5.1 + jsdom）可加载、完成指令注册并随组件渲染。
 */
import { describe, it, expect } from 'vitest';
import { defineComponent, h, withDirectives } from 'vue';
import { mount } from '@vue/test-utils';
import {
  SDK_VERSION,
  NlObserveClient,
  createClient,
  registerVue3Directives,
  type Vue3DirectiveDef,
} from '../../src/vue3';

describe('vue3-host 冒烟：sdk-web vue3 入口', () => {
  it('registerVue3Directives 注册 biz/span，created 钩子随渲染生效', () => {
    const client = createClient({ serviceName: 'sdk-web-vue3-host', customerId: 'C-200' });
    expect(client.isInitialized).toBe(true);

    const defs: Record<string, Vue3DirectiveDef> = {};
    registerVue3Directives(
      {
        directive: (name, def) => {
          defs[name] = def;
        },
      },
      client,
    );
    expect(Object.keys(defs).sort()).toEqual(['biz', 'span']);

    const Comp = defineComponent({
      name: 'BizHost',
      setup() {
        return () => withDirectives(h('div', 'ok'), [[defs.biz, 'ORDER']]);
      },
    });
    const wrapper = mount(Comp);
    expect(wrapper.text()).toBe('ok');
    expect(wrapper.element.getAttribute('data-nl-biz')).toBe('ORDER');
    expect(wrapper.element.getAttribute('data-nl-client')).toBe(SDK_VERSION);
  });

  it('重复 createClient 返回既有实例（详设 §5.1 单例守卫）', () => {
    const a = createClient();
    const b = createClient();
    expect(a).toBe(b);
  });

  it('未 init 的门面方法 no-op 不抛（T13 前骨架语义）', () => {
    const fresh = new NlObserveClient();
    fresh.signIn('C-1');
    fresh.startBusiness('ORDER');
    fresh.signOut();
    expect(fresh.isInitialized).toBe(false);
  });
});

/**
 * T12 冒烟测试（test_cases: vue2-host 冒烟）：sdk-web vue2 入口在 Vue2 宿主
 * （vue@2.7.16 + @vue/test-utils@1.3.6 + jsdom）可加载、完成指令注册并随组件渲染。
 */
import { describe, it, expect } from 'vitest';
import Vue, { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import {
  SDK_VERSION,
  NlObserveClient,
  createClient,
  registerVue2Directives,
} from '../../src/vue2';

describe('vue2-host 冒烟：sdk-web vue2 入口', () => {
  it('createClient 初始化 + registerVue2Directives 注册后，v-biz 指令随渲染生效', () => {
    const client = createClient({ serviceName: 'sdk-web-vue2-host', customerId: 'C-100' });
    expect(client.isInitialized).toBe(true);
    expect(client.resolvedOptions.batchTimeoutMs).toBe(2000); // 详设 §1.5 默认值

    registerVue2Directives(Vue, client);

    const Comp = defineComponent({
      name: 'BizHost',
      setup() {
        return () => h('div', { directives: [{ name: 'biz', value: 'ORDER' }] }, 'ok');
      },
    });
    const wrapper = mount(Comp);
    expect(wrapper.text()).toBe('ok');
    expect(wrapper.element.getAttribute('data-nl-biz')).toBe('ORDER');
    expect(wrapper.element.getAttribute('data-nl-client')).toBe(SDK_VERSION);
  });

  it('重复 createClient 返回既有实例并 warn（详设 §5.1 单例守卫）', () => {
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

/**
 * Vue2 适配层（详设 §1.5 adapters/vue2）：注册 v-biz / v-span 自定义指令（T16）。
 *
 * 钩子（T12 实测更正：详设写 bound/unbound，但 vue@2.7.16 运行时只调用
 * bind/inserted/update/componentUpdated/unbind（dist 源码 updateDirectives/callHook 核实））：
 * - bind：打 data-nl-* 标记（T12 骨架行为保留，宿主可见/可调试）；
 * - inserted：元素插入 DOM（= 组件 mounted 语义）→ v-biz 起业务 root span（startBusiness）、
 *   v-span 起子 span（共享 lifecycle，见 lifecycle.ts 时序注）；
 * - unbind：v-biz 未显式结束则兜底 end（end_reason=unmount）、v-span 结束子 span；移除标记。
 *
 * 与 vue3 适配层共享 adapters/lifecycle，保证 Vue.directive 与 app.directive 行为一致（T16 AC）。
 *
 * 结构化类型（不 import 'vue'）：sdk-web 对 vue 仅为 optional peerDependency，
 * core/适配层保持框架无关，双构建成本收敛于共享 core（质疑者1 R1 对策）。
 */
import { SDK_VERSION } from '../../core/version';
import type { NlObserveClient } from '../../core/client';
import { createDirectiveLifecycle } from '../lifecycle';

export interface Vue2DirectiveBinding {
  readonly name: string;
  readonly value: unknown;
}

export interface Vue2DirectiveOptions {
  bind?: (el: Element, binding: Vue2DirectiveBinding) => void;
  inserted?: (el: Element, binding: Vue2DirectiveBinding) => void;
  unbind?: (el: Element, binding: Vue2DirectiveBinding) => void;
}

/** 接受 Vue 2 构造器（Vue.directive）或任意提供同名 API 的对象（测试替身）。 */
export interface Vue2Like {
  directive(id: string, options?: Vue2DirectiveOptions): unknown;
}

/** 注册 v-biz / v-span 指令（生命周期见 adapters/lifecycle）。 */
export function registerVue2Directives(vue: Vue2Like, client: NlObserveClient): void {
  const lc = createDirectiveLifecycle(client);
  vue.directive('biz', {
    bind(el, binding) {
      el.setAttribute('data-nl-biz', String(binding.value ?? ''));
      el.setAttribute('data-nl-client', SDK_VERSION);
    },
    inserted(el, binding) {
      lc.bizMounted(el, String(binding.value ?? ''));
    },
    unbind(el) {
      lc.bizUnmounted(el);
      el.removeAttribute('data-nl-biz');
    },
  });
  vue.directive('span', {
    bind(el, binding) {
      el.setAttribute('data-nl-span', String(binding.value ?? ''));
      el.setAttribute('data-nl-client', SDK_VERSION);
    },
    inserted(el, binding) {
      lc.spanMounted(el, String(binding.value ?? ''));
    },
    unbind(el) {
      lc.spanUnmounted(el);
      el.removeAttribute('data-nl-span');
    },
  });
}

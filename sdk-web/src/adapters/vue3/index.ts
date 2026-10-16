/**
 * Vue3 适配层（详设 §1.5 adapters/vue3）：注册 v-biz / v-span 自定义指令（T16）。
 *
 * 钩子（与 vue2 适配层钩子语义对齐：bind↔created、inserted↔mounted、unbind↔unmounted）：
 * - created：打 data-nl-* 标记（T12 骨架行为保留，宿主可见/可调试）；
 * - mounted：宿主组件挂载完成（post-render effect，= 组件 mounted 语义）→ v-biz 起业务
 *   root span（startBusiness）、v-span 起子 span（共享 lifecycle，见 lifecycle.ts 时序注）；
 * - unmounted：v-biz 未显式结束则兜底 end（end_reason=unmount）、v-span 结束子 span；移除标记。
 *
 * 与 vue2 适配层共享 adapters/lifecycle，保证 app.directive 与 Vue.directive 行为一致（T16 AC）。
 *
 * Vue3DirectiveDef 与 @vue/runtime-core 的 Directive 结构兼容（少参兼容），宿主可直接
 * withDirectives / app.directive / app.use 使用；不 import 'vue'：sdk-web 对 vue 仅为
 * optional peerDependency，保持框架无关。
 */
import { SDK_VERSION } from '../../core/version';
import type { NlObserveClient } from '../../core/client';
import { createDirectiveLifecycle } from '../lifecycle';

export interface Vue3DirectiveBinding {
  readonly value: unknown;
}

export interface Vue3DirectiveDef {
  created?: (el: Element, binding: Vue3DirectiveBinding) => void;
  mounted?: (el: Element, binding: Vue3DirectiveBinding) => void;
  unmounted?: (el: Element, binding: Vue3DirectiveBinding) => void;
}

/** 接受 Vue 3 应用实例（app.directive）或任意提供同名 API 的对象（测试替身）。 */
export interface Vue3AppLike {
  directive(name: string, definition: Vue3DirectiveDef): unknown;
}

/** 注册 v-biz / v-span 指令（生命周期见 adapters/lifecycle）。 */
export function registerVue3Directives(app: Vue3AppLike, client: NlObserveClient): void {
  const lc = createDirectiveLifecycle(client);
  app.directive('biz', {
    created(el, binding) {
      el.setAttribute('data-nl-biz', String(binding.value ?? ''));
      el.setAttribute('data-nl-client', SDK_VERSION);
    },
    mounted(el, binding) {
      lc.bizMounted(el, String(binding.value ?? ''));
    },
    unmounted(el) {
      lc.bizUnmounted(el);
      el.removeAttribute('data-nl-biz');
    },
  });
  app.directive('span', {
    created(el, binding) {
      el.setAttribute('data-nl-span', String(binding.value ?? ''));
      el.setAttribute('data-nl-client', SDK_VERSION);
    },
    mounted(el, binding) {
      lc.spanMounted(el, String(binding.value ?? ''));
    },
    unmounted(el) {
      lc.spanUnmounted(el);
      el.removeAttribute('data-nl-span');
    },
  });
}

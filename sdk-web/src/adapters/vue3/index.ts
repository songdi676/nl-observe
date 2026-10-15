/**
 * Vue3 适配层（详设 §1.5 adapters/vue3）：注册 v-biz / v-span 自定义指令。
 * 钩子 created/unmounted。骨架阶段指令仅打 data-nl-* 标记；
 * T13/T16 由指令钩子驱动业务生命周期（与 vue2 适配层共享 core，行为对齐）。
 *
 * Vue3DirectiveDef 与 @vue/runtime-core 的 Directive 结构兼容（created/unmounted 钩子
 * 参数少参兼容），宿主可直接 withDirectives / app.use 使用；
 * 不 import 'vue'：sdk-web 对 vue 仅为 optional peerDependency，保持框架无关。
 */
import { SDK_VERSION } from '../../core/version';
import type { NlObserveClient } from '../../core/client';

export interface Vue3DirectiveBinding {
  readonly value: unknown;
}

export interface Vue3DirectiveDef {
  created?: (el: Element, binding: Vue3DirectiveBinding) => void;
  unmounted?: (el: Element, binding: Vue3DirectiveBinding) => void;
}

/** 接受 Vue 3 应用实例（app.directive）或任意提供同名 API 的对象（测试替身）。 */
export interface Vue3AppLike {
  directive(name: string, definition: Vue3DirectiveDef): unknown;
}

/** 注册 v-biz / v-span 指令；client 供 T13 生命周期钩子使用（骨架阶段保留参数）。 */
export function registerVue3Directives(app: Vue3AppLike, client: NlObserveClient): void {
  void client;
  app.directive('biz', {
    created(el, binding) {
      el.setAttribute('data-nl-biz', String(binding.value ?? ''));
      el.setAttribute('data-nl-client', SDK_VERSION);
    },
    unmounted(el) {
      el.removeAttribute('data-nl-biz');
    },
  });
  app.directive('span', {
    created(el, binding) {
      el.setAttribute('data-nl-span', String(binding.value ?? ''));
      el.setAttribute('data-nl-client', SDK_VERSION);
    },
    unmounted(el) {
      el.removeAttribute('data-nl-span');
    },
  });
}

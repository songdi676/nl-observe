/**
 * Vue2 适配层（详设 §1.5 adapters/vue2）：注册 v-biz / v-span 自定义指令。
 * 骨架阶段指令仅打 data-nl-* 标记；T13/T16 由指令钩子驱动业务生命周期
 * （v-biz 绑定 startBusiness、v-span 组件挂起/恢复）。
 *
 * 钩子命名更正（T12 实测）：详设写 bound/unbound，但 vue@2.7.16 运行时只调用
 * bind/inserted/update/componentUpdated/unbind（dist 源码 updateDirectives/callHook 核实，
 * 无 bound/unbound 别名）——故此处按运行时真实钩子 bind/unbind 实现，语义与详设一致。
 *
 * 结构化类型（不 import 'vue'）：sdk-web 对 vue 仅为 optional peerDependency，
 * core/适配层保持框架无关，双构建成本收敛于共享 core（质疑者1 R1 对策）。
 */
import { SDK_VERSION } from '../../core/version';
import type { NlObserveClient } from '../../core/client';

export interface Vue2DirectiveBinding {
  readonly name: string;
  readonly value: unknown;
}

export interface Vue2DirectiveOptions {
  bind?: (el: Element, binding: Vue2DirectiveBinding) => void;
  unbind?: (el: Element, binding: Vue2DirectiveBinding) => void;
}

/** 接受 Vue 2 构造器（Vue.directive）或任意提供同名 API 的对象（测试替身）。 */
export interface Vue2Like {
  directive(id: string, options?: Vue2DirectiveOptions): unknown;
}

/** 注册 v-biz / v-span 指令；client 供 T13 生命周期钩子使用（骨架阶段保留参数）。 */
export function registerVue2Directives(vue: Vue2Like, client: NlObserveClient): void {
  void client;
  vue.directive('biz', {
    bind(el, binding) {
      el.setAttribute('data-nl-biz', String(binding.value ?? ''));
      el.setAttribute('data-nl-client', SDK_VERSION);
    },
    unbind(el) {
      el.removeAttribute('data-nl-biz');
    },
  });
  vue.directive('span', {
    bind(el, binding) {
      el.setAttribute('data-nl-span', String(binding.value ?? ''));
      el.setAttribute('data-nl-client', SDK_VERSION);
    },
    unbind(el) {
      el.removeAttribute('data-nl-span');
    },
  });
}

/**
 * @nl-observe/sdk-web Vue3 入口（peerDep vue@^3）：core + Vue3 指令适配层。
 * 与 index 共享 core（双构建成本收敛，详设 §1.5 / 质疑者1 R1 对策）。
 */
export * from './index';
export { registerVue3Directives } from './adapters/vue3';
export type { Vue3AppLike, Vue3DirectiveBinding, Vue3DirectiveDef } from './adapters/vue3';

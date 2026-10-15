/**
 * @nl-observe/sdk-web Vue2 入口（peerDep vue@^2）：core + Vue2 指令适配层。
 * 与 index 共享 core（双构建成本收敛，详设 §1.5 / 质疑者1 R1 对策）。
 */
export * from './index';
export { registerVue2Directives } from './adapters/vue2';
export type { Vue2DirectiveBinding, Vue2DirectiveOptions, Vue2Like } from './adapters/vue2';

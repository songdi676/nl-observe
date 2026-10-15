/**
 * @nl-observe/sdk-web 默认入口（框架无关 core，详设 §1.5）。
 * Vue 用户请从 ./vue2 或 ./vue3 入口引入（双构建：tsup es+cjs，质疑者1 R1 对策：共享 core）。
 */
export { SDK_VERSION } from './core/version';
export { DEFAULT_BATCH_TIMEOUT_MS, NlObserveClient, createClient } from './core/client';
export type { NlObserveOptions, ResolvedOptions } from './core/client';
export type { NlObserveGuardEntry } from './core/guard';
export { SigninManager } from './core/SigninManager';
export type { SigninState } from './core/SigninManager';
export { BusinessManager } from './core/BusinessManager';
export type { BizEndReason, BizStatus, BusinessState } from './core/BusinessManager';

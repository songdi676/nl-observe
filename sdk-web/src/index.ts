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
// T16：vue 指令共享生命周期（框架无关，vue2/vue3 入口经 `export * from './index'` 一并导出）
export { VSPAN_NAME_PREFIX, createDirectiveLifecycle } from './adapters/lifecycle';
export type { DirectiveLifecycle } from './adapters/lifecycle';
export {
  BIZ_BUSINESS_NAME_PREFIX,
  ATTR_BIZ_CODE,
  ATTR_BIZ_STATUS,
  ATTR_BIZ_END_REASON,
  ATTR_BIZ_TOTAL_SUSPENDED_MS,
  ATTR_BIZ_EFFECTIVE_DURATION_MS,
  ATTR_BIZ_TRACE_ID,
  EVENT_BIZ_SUSPEND_START,
  EVENT_BIZ_RESUME,
  METRIC_BUSINESS_DURATION,
  BusinessManager,
} from './core/BusinessManager';
export type {
  ActiveBusiness,
  BizEndReason,
  BizStatus,
  BusinessState,
  BusinessDurationAttrs,
} from './core/BusinessManager';

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
export type { BizEndReason, BizStatus, BusinessState, BusinessDurationAttrs } from './core/BusinessManager';
export {
  METRIC_FIRST_SCREEN_LOAD,
  METRIC_SDK_FLUSH,
  FLUSH_MODE_BEACON,
  ATTR_FLUSH,
  ATTR_FLUSH_RESULT,
  ATTR_DROPPED_SPANS,
  trackFirstScreenLoad,
} from './core/perf/metrics';
export type { FlushResult, LcpObserverCtor, WindowLike } from './core/perf/metrics';
export {
  DEFAULT_FLUSH_TIMEOUT_MS,
  DropCountingSpanExporter,
  SendBeaconMetricExporter,
  SendBeaconSpanExporter,
  createOtlpHttpBeaconTransport,
  metricsUrlFromTracesUrl,
  setupFlushHook,
} from './core/flush';
export type { BeaconTransport } from './core/flush';
export {
  ATTR_HTTP_REQUEST_METHOD,
  ATTR_HTTP_ROUTE,
  ATTR_HTTP_RESPONSE_STATUS_CODE,
  ATTR_NL_DURATION_MS,
  REST_SPAN_NAME_PREFIX,
  recordRestSpan,
  installRestInstrumentation,
} from './core/rest/autoprofile';
export type { RestInstrumentationHandle, RestRecorderDeps, RestSpanInput } from './core/rest/autoprofile';

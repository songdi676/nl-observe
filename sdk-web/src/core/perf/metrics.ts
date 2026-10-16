/**
 * 性能指标采集（详设 §1.5 perf/metrics.ts，T15）。
 *
 * - nl.first_screen_load：每页恰好一个 data point——优先 LCP（PerformanceObserver
 *   buffered 首批的末条 startTime，即当前最大候选），无 LCP 能力时 load 事件兜底；
 *   先到者上报一次，随后断开 observer 并移除 load 监听（防重复/防内存泄漏）。
 * - nl.sdk.flush：flush 尽力上报的形态属性常量（flush=beacon / result=ok|fail|timeout /
 *   dropped_spans=N），由 flush.ts 的 hook 写入。
 *
 * 指标均为 Counter（sum 形态 + DELTA  temporality，T13 锁定勿回归）；
 * 浏览器 API 经 WindowLike/PerformanceObserver 注入点抽象——node 单测无需 jsdom。
 */

export const METRIC_FIRST_SCREEN_LOAD = 'nl.first_screen_load';
export const METRIC_SDK_FLUSH = 'nl.sdk.flush';

/** flush 指标 data point 属性（详设 §4.2；后端 metric 属性按 JSON 原样入库，无白名单约束） */
export const FLUSH_MODE_BEACON = 'beacon';
export const ATTR_FLUSH = 'flush';
export const ATTR_FLUSH_RESULT = 'result';
export const ATTR_DROPPED_SPANS = 'dropped_spans';

export type FlushResult = 'ok' | 'fail' | 'timeout';

/** PerformanceObserver 回调收到的 entries 列表（仅取 startTime，兼容 LCP 与 mock） */
export interface LcpEntryList {
  getEntries(): Array<{ startTime: number }>;
}

/** PerformanceObserver 最小面（可注入 mock） */
export interface LcpObserverLike {
  observe(options?: unknown): void;
  disconnect(): void;
}

export type LcpObserverCtor = new (callback: (list: LcpEntryList) => void) => LcpObserverLike;

/** window 最小面（可注入 fake：监听注册/派发/visibilityState） */
export interface WindowLike {
  addEventListener(type: string, handler: () => void): void;
  removeEventListener(type: string, handler: () => void): void;
  visibilityState(): string;
}

/** 缺省 window（node/SSR → null）。 */
export function defaultWindowLike(): WindowLike | null {
  const w = (globalThis as unknown as { window?: WindowLike }).window;
  return typeof w?.addEventListener === 'function' ? w : null;
}

/** 缺省 PerformanceObserver 构造器（node 无 LCP entry type → observe 时 try/catch 兜底）。 */
export function defaultLcpObserverCtor(): LcpObserverCtor | null {
  const ctor = (globalThis as unknown as { PerformanceObserver?: LcpObserverCtor }).PerformanceObserver;
  return typeof ctor === 'function' ? ctor : null;
}

/** load 兜底耗时：navigation 计时条目 loadEventEnd（相对 timeOrigin），缺省 performance.now()。 */
function defaultLoadTimeMs(): number {
  const perf = (globalThis as { performance?: Performance }).performance;
  if (perf && typeof perf.getEntriesByType === 'function') {
    const nav = perf.getEntriesByType('navigation')[0] as
      | (PerformanceEntry & { loadEventEnd?: number })
      | undefined;
    if (nav && typeof nav.loadEventEnd === 'number' && nav.loadEventEnd > 0) {
      return nav.loadEventEnd;
    }
  }
  return perf && typeof perf.now === 'function' ? perf.now() : 0;
}

export interface FirstScreenLoadDeps {
  /** 上报唯一 data point（client 接 Counter.add） */
  emit: (valueMs: number) => void;
  /** 注入 PerformanceObserver 构造器；显式 null = 禁用 LCP 走 load 兜底；缺省探测全局 */
  performanceObserver?: LcpObserverCtor | null;
  /** 注入 window；显式 null = 不监听 load；缺省探测全局 */
  windowLike?: WindowLike | null;
  /** load 兜底取值（缺省 navigation loadEventEnd / performance.now()） */
  loadTimeMs?: () => number;
}

export interface FirstScreenLoadTracker {
  /** 移除监听/断开 observer（client.shutdown 调用） */
  dispose(): void;
}

/**
 * 首屏指标跟踪器：LCP 优先、load 兜底，每页仅一个 data point。
 * LCP 语义：buffered 首批末条 = 当前最大候选（页面生命周期内 LCP 还会变，
 * 但「每页仅一个」验收要求先到者定稿——首批即代表用户可感知的首屏完成态）。
 */
export function trackFirstScreenLoad(deps: FirstScreenLoadDeps): FirstScreenLoadTracker {
  let reported = false;
  let observer: LcpObserverLike | undefined;
  let loadHandler: (() => void) | undefined;

  const cleanup = (): void => {
    observer?.disconnect();
    observer = undefined;
    const win = deps.windowLike !== undefined ? deps.windowLike : defaultWindowLike();
    if (win && loadHandler) {
      win.removeEventListener('load', loadHandler);
    }
    loadHandler = undefined;
  };

  const report = (valueMs: number): void => {
    if (reported) return;
    reported = true;
    deps.emit(valueMs);
    cleanup();
  };

  const win = deps.windowLike !== undefined ? deps.windowLike : defaultWindowLike();
  const poCtor = deps.performanceObserver !== undefined ? deps.performanceObserver : defaultLcpObserverCtor();

  if (poCtor) {
    try {
      observer = new poCtor((list) => {
        const entries = list.getEntries();
        if (entries.length === 0) return;
        report(entries[entries.length - 1].startTime);
      });
      observer.observe({ type: 'largest-contentful-paint', buffered: true });
    } catch {
      // node/旧浏览器不支持 LCP entry type → 退回 load 兜底
      observer = undefined;
    }
  }

  if (win && !reported) {
    loadHandler = () => report((deps.loadTimeMs ?? defaultLoadTimeMs)());
    win.addEventListener('load', loadHandler);
  }

  return { dispose: cleanup };
}

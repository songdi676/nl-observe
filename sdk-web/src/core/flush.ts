/**
 * 页面卸载 flush（详设 §1.5 flush.ts，T15）。
 *
 * sendBeacon 只支持文本载荷 → OTLP/HTTP **JSON 文本**口径（@opentelemetry/otlp-transformer
 * 的 JsonTraceSerializer / JsonMetricsSerializer，与 T05 base64/hex 口径一致）；
 * 非浏览器环境（node 单测）回退 fetch POST（死端点即真实失败路径）。
 *
 * 语义（验收标准 b/c/d）：
 * - pagehide / visibilitychange=hidden → forceFlush（trace + metric provider）；
 * - BSP 内建 document-hide 自动 flush 关闭（disableAutoFlushOnDocumentHide），
 *   统一由本 hook 拥有：避免双 beacon，且每次 flush 都尽力上报 nl.sdk.flush 指标
 *   （result=ok|fail|timeout，dropped_spans=N）；
 * - flush 失败/超时 → nl.sdk.flush data point（尽力）；flush 指标自身再导出失败
 *   （二次失败）→ 仅 console.warn，绝不抛错/挂起页面卸载。
 * - forceFlush 超时（flushTimeoutMs，默认 2000ms 可配）→ result=timeout。
 */
import { ExportResultCode, type ExportResult } from '@opentelemetry/core';
import { JsonTraceSerializer, JsonMetricsSerializer } from '@opentelemetry/otlp-transformer';
import {
  AggregationTemporality,
  type InstrumentType,
  type PushMetricExporter,
  type ResourceMetrics,
} from '@opentelemetry/sdk-metrics';
import type { ReadableSpan, SpanExporter } from '@opentelemetry/sdk-trace-base';
import { defaultWindowLike, type FlushResult, type WindowLike } from './perf/metrics';

export const DEFAULT_FLUSH_TIMEOUT_MS = 2000;

/** OTLP/HTTP 文本传输（浏览器 sendBeacon；node 回退 fetch）。 */
export type BeaconTransport = (url: string, body: string) => Promise<void>;

const textDecoder = new TextDecoder();

/**
 * T17 降级策略（NFR-03）：endpoint 不可达/超时 → console.debug 记录后仍抛错——
 * 抛错是 exporter 契约（回调 FAILED → DropCounting 计 dropped_spans、flush 分类 fail），
 * 「不抛错、不阻断业务」由上层保证（BSP/reader 吞错 + flush hook best-effort），
 * 业务 span 照常本地生成。
 *
 * timeoutMs：fetch 回退路径的请求超时（AbortSignal.timeout）；浏览器 sendBeacon 路径
 * 为 fire-and-forget 队列（无请求级超时），整体冲刷由 flush hook 的 flushTimeoutMs 兜底。
 */
export function createOtlpHttpBeaconTransport(timeoutMs?: number): BeaconTransport {
  return async (url, body) => {
    try {
      const nav = (globalThis as unknown as {
        navigator?: { sendBeacon?: (u: string, d: unknown) => boolean };
      }).navigator;
      if (typeof nav?.sendBeacon === 'function' && typeof globalThis.Blob === 'function') {
        // sendBeacon 只能带文本/Blob：OTLP/JSON 文本口径，Blob 声明 content-type
        const ok = nav.sendBeacon(url, new Blob([body], { type: 'application/json' }));
        if (!ok) {
          throw new Error(`sendBeacon rejected for ${url} (queue full/unsupported) → OTLP/HTTP beacon export failed`);
        }
        return;
      }
      const init: RequestInit = {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
      };
      if (timeoutMs !== undefined && timeoutMs > 0 && typeof AbortSignal !== 'undefined' && 'timeout' in AbortSignal) {
        init.signal = AbortSignal.timeout(timeoutMs);
      }
      const res = await fetch(url, init);
      if (!res.ok) {
        throw new Error(`OTLP/HTTP export failed: ${res.status} ${url}`);
      }
    } catch (err) {
      console.debug('[nl-observe] OTLP/HTTP export degraded (endpoint unreachable or timeout):', url, err);
      throw err;
    }
  };
}

/** /v1/traces → /v1/metrics；base URL → 追加 /v1/metrics（OTLP/HTTP 单接收端口约定）。 */
export function metricsUrlFromTracesUrl(url: string): string {
  const trimmed = url.replace(/\/+$/, '');
  if (trimmed.endsWith('/v1/traces')) {
    return `${trimmed.slice(0, -'/traces'.length)}/metrics`;
  }
  return `${trimmed}/v1/metrics`;
}

export interface SendBeaconExporterConfig {
  url: string;
  transport: BeaconTransport;
}

/** trace 导出：OTLP/JSON 文本经 beacon/fetch 发送（SpanExporter 回调式 API）。 */
export class SendBeaconSpanExporter implements SpanExporter {
  constructor(private readonly config: SendBeaconExporterConfig) {}

  export(spans: ReadableSpan[], resultCallback: (result: ExportResult) => void): void {
    void this.doExport(spans).then(
      () => resultCallback({ code: ExportResultCode.SUCCESS }),
      () => resultCallback({ code: ExportResultCode.FAILED }),
    );
  }

  async doExport(spans: ReadableSpan[]): Promise<void> {
    const bytes = JsonTraceSerializer.serializeRequest(spans);
    if (!bytes) return; // 空请求无载荷
    await this.config.transport(this.config.url, textDecoder.decode(bytes));
  }

  forceFlush(): Promise<void> {
    return Promise.resolve();
  }

  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}

/**
 * metric 导出：OTLP/JSON 文本经 beacon/fetch 发送。
 * Counter 必须 DELTA（T13 锁定：cumulative 重发 → 后端 append 表重复行）。
 * reader 吞掉导出错误（globalErrorHandler 静默）→ 本导出器自行记录 lastError，
 * 供 flush hook 分类 fail 并检测二次失败。
 */
export class SendBeaconMetricExporter implements PushMetricExporter {
  private lastError: unknown;

  constructor(private readonly config: SendBeaconExporterConfig) {}

  export(metrics: ResourceMetrics, resultCallback: (result: ExportResult) => void): void {
    void this.doExport(metrics).then(
      () => {
        this.lastError = undefined; // 成功清零：陈旧失败不得驻留污染后续 flush 分类（Major-1 源头）
        resultCallback({ code: ExportResultCode.SUCCESS });
      },
      (err: unknown) => {
        this.lastError = err;
        resultCallback({ code: ExportResultCode.FAILED });
      },
    );
  }

  async doExport(metrics: ResourceMetrics): Promise<void> {
    const bytes = JsonMetricsSerializer.serializeRequest(metrics);
    if (!bytes) return;
    await this.config.transport(this.config.url, textDecoder.decode(bytes));
  }

  selectAggregationTemporality(_instrumentType?: InstrumentType): AggregationTemporality {
    // 全部 DELTA：Counter 累计值经 reader 周期切分为增量（T13 锁定，cumulative 重发
    // → 后端 append 表重复行）
    void _instrumentType;
    return AggregationTemporality.DELTA;
  }

  /** 最近一次导出失败原因（reader 静默吞错，hook 需自行查询）。 */
  getLastError(): unknown {
    return this.lastError;
  }

  resetLastError(): void {
    this.lastError = undefined;
  }

  forceFlush(): Promise<void> {
    return Promise.resolve();
  }

  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}

/**
 * 包装 SpanExporter 计数 dropped_spans（BSP 队列内部状态不可见，只能按批计）：
 * - 失败回调 → 该批全部计入（页面卸载即随批丢失）
 * - 在途（回调未回，如导出挂起被 hook 超时）→ 计入（超时即丢）
 * - 成功回调 → 清零（队列已排空）
 */
export class DropCountingSpanExporter implements SpanExporter {
  private dropped = 0;
  private inFlight = 0;

  constructor(private readonly inner: SpanExporter) {}

  export(spans: ReadableSpan[], resultCallback: (result: ExportResult) => void): void {
    this.inFlight = spans.length;
    this.inner.export(spans, (result) => {
      if (result.code === ExportResultCode.SUCCESS) {
        this.dropped = 0;
        this.inFlight = 0;
      } else {
        this.dropped += this.inFlight;
        this.inFlight = 0;
      }
      resultCallback(result);
    });
  }

  /** 已确认丢失 + 在途（超时即丢失）的 span 数 */
  getDropped(): number {
    return this.dropped + this.inFlight;
  }

  forceFlush(): Promise<void> {
    return this.inner.forceFlush ? this.inner.forceFlush() : Promise.resolve();
  }

  shutdown(): Promise<void> {
    return this.inner.shutdown();
  }
}

export class FlushTimeoutError extends Error {
  constructor(ms: number) {
    super(`flush timed out after ${ms}ms`);
    this.name = 'FlushTimeoutError';
  }
}

function withTimeout(p: Promise<void>, ms: number): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new FlushTimeoutError(ms)), ms);
    // 双 handler 确保底层 promise 的 reject 永远被处理（超时后底层才失败也不产生 unhandled rejection）
    void p.then(
      () => {
        clearTimeout(timer);
        resolve();
      },
      (err: unknown) => {
        clearTimeout(timer);
        reject(err);
      },
    );
  });
}

export interface FlushHookDeps {
  /** 统一 flush（trace + metric provider 的 forceFlush） */
  forceFlushAll: () => Promise<void>;
  /** 已丢/在途 span 数（DropCountingSpanExporter） */
  getDroppedSpans: () => number;
  /** 尽力上报 nl.sdk.flush data point（result, droppedSpans） */
  reportFlush: (result: FlushResult, droppedSpans: number) => void;
  /** 二次 flush：把 nl.sdk.flush data point 本身推出去（metric reader forceFlush） */
  flushAgain?: () => Promise<void>;
  /** 查询 metric 导出最近错误（reader 静默吞错） */
  getExportError?: () => unknown;
  /** 清除 metric 导出错误（二次 flush 前重置，区分首/次失败） */
  resetExportError?: () => void;
  /** forceFlush 超时（缺省 2000ms） */
  flushTimeoutMs?: number;
  /** 注入 window（缺省全局探测；node → null 即不挂监听） */
  windowLike?: WindowLike | null;
}

export interface FlushHook {
  /** 立即 flush（去重：进行中不再重入；best-effort 绝不抛错） */
  flushNow(): Promise<void>;
  /** 移除 pagehide/visibilitychange 监听（client.shutdown 调用） */
  dispose(): void;
}

/**
 * 页面卸载 flush hook：pagehide / visibilitychange=hidden → forceFlush，
 * 每次尝试尽力上报 nl.sdk.flush（ok/fail/timeout + dropped_spans）。
 */
export function setupFlushHook(deps: FlushHookDeps): FlushHook {
  const flushTimeoutMs = deps.flushTimeoutMs ?? DEFAULT_FLUSH_TIMEOUT_MS;
  const win = deps.windowLike !== undefined ? deps.windowLike : defaultWindowLike();
  let flushing = false;

  const flushNow = async (): Promise<void> => {
    if (flushing) return; // 同一卸载序列（visibilitychange + pagehide）去重，避免双 beacon
    flushing = true;
    try {
      let result: FlushResult;
      try {
        // 先重置：result 分类只反映本次尝试内的导出错误——周期导出（batchTimeout 间隔）
        // 留下的陈旧 lastError 不得污染本次 result（评审 Major-1：成功 flush 被误判 fail）
        deps.resetExportError?.();
        await withTimeout(Promise.resolve(deps.forceFlushAll()), flushTimeoutMs);
        // forceFlush resolve 但 metric 导出被 reader 静默吞错 → 仍算 fail
        result = deps.getExportError?.() !== undefined ? 'fail' : 'ok';
      } catch (err) {
        result = err instanceof FlushTimeoutError ? 'timeout' : 'fail';
      }

      const droppedSpans = result === 'ok' ? 0 : deps.getDroppedSpans();
      deps.reportFlush(result, droppedSpans);

      // 把 nl.sdk.flush data point 本身再推一次（尽力）；二次失败仅 console，绝不抛错
      deps.resetExportError?.();
      if (deps.flushAgain) {
        try {
          await withTimeout(Promise.resolve(deps.flushAgain()), flushTimeoutMs);
        } catch {
          // 二次 flush 超时/失败 → 落入下方 console
        }
      }
      const secondaryError = deps.getExportError?.();
      if (secondaryError !== undefined) {
        console.warn('[nl-observe] flush metric re-export failed (secondary, best-effort):', secondaryError);
      }
    } catch (err) {
      console.warn('[nl-observe] flush hook error (best-effort, never throws):', err);
    } finally {
      flushing = false;
    }
  };

  const onPageHide = (): void => {
    void flushNow();
  };
  const onVisibilityChange = (): void => {
    // visibilityState 是属性（真实 DOM 形态，经 defaultWindowLike 适配器读 document）——勿改回方法调用
    if (win?.visibilityState === 'hidden') {
      void flushNow();
    }
  };
  if (win) {
    win.addEventListener('pagehide', onPageHide);
    win.addEventListener('visibilitychange', onVisibilityChange);
  }

  const dispose = (): void => {
    if (win) {
      win.removeEventListener('pagehide', onPageHide);
      win.removeEventListener('visibilitychange', onVisibilityChange);
    }
  };

  return { flushNow, dispose };
}

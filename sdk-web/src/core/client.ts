/**
 * NlObserveClient 门面（详设 §1.5 core/client.ts）。
 *
 * init 装配 OTel WebTracerProvider + MeterProvider（版本按 poc-notes §2 锁定，API 按
 * 「OTel JS 2.x 变化」四条编写：resourceFromAttributes / spanProcessors 构造器传入 /
 * 不全局 register——POC 发现 4，tracer 经 provider.getTracer 显式获取）。
 * T13 落地：signIn/signOut 经 SigninManager 状态机（互斥：已签入再签入先隐式 sign_out）；
 * startBusiness/suspendBusiness/resumeBusiness/endBusiness 经 BusinessManager
 * （挂起/恢复/有效耗时，end 发 nl.business_duration data point）。
 * T14 落地：REST 自动埋点（fetch/XHR 包装，rest/autoprofile）+ trackRest 手动兜底；
 * T15 落地：otlpUrl 时 trace 走 SendBeaconSpanExporter（OTLP/HTTP JSON 文本 +
 * navigator.sendBeacon），metric 走 SendBeaconMetricExporter（/v1/metrics，DELTA）；
 * pagehide/visibilitychange=hidden → flush hook（尽力上报 nl.sdk.flush）；
 * nl.first_screen_load 每页一个 data point（LCP 优先，load 兜底）。
 * T16 落地：businessState/activeBusiness 供 vue 指令（v-biz unmount 兜底、v-span 取父）查询。
 * T17 落地：init 配置规范面（endpoint/exportTimeoutMs/restPathFilter，otlpUrl 兼容别名）；
 * OTLP/HTTP 导出接线闭环（WebTracerProvider + traces/metrics exporter，业务 root span
 * 以 ROOT_CONTEXT 为父保证不同 traceId）；endpoint 不可达 → 静默降级（console.debug，
 * 不抛错、不阻断业务办理，NFR-03）。
 */
import type { Tracer } from '@opentelemetry/api';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { BatchSpanProcessor, type SpanProcessor } from '@opentelemetry/sdk-trace-base';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  MeterProvider,
  PeriodicExportingMetricReader,
  type IMetricReader,
} from '@opentelemetry/sdk-metrics';
import { SDK_VERSION } from './version';
import { getGuardedClient, setGuardedClient } from './guard';
import { installSilentDiagnostics } from './degrade';
import { SigninManager } from './SigninManager';
import {
  BusinessManager,
  METRIC_BUSINESS_DURATION,
  type ActiveBusiness,
  type BizEndReason,
  type BizStatus,
  type BusinessState,
} from './BusinessManager';
import {
  trackFirstScreenLoad,
  METRIC_FIRST_SCREEN_LOAD,
  METRIC_SDK_FLUSH,
  ATTR_DROPPED_SPANS,
  ATTR_FLUSH,
  ATTR_FLUSH_RESULT,
  FLUSH_MODE_BEACON,
  type FirstScreenLoadTracker,
  type LcpObserverCtor,
  type WindowLike,
} from './perf/metrics';
import {
  DropCountingSpanExporter,
  SendBeaconMetricExporter,
  SendBeaconSpanExporter,
  createOtlpHttpBeaconTransport,
  metricsUrlFromTracesUrl,
  setupFlushHook,
  type FlushHook,
} from './flush';
import {
  installRestInstrumentation,
  recordRestSpan,
  type RestInstrumentationHandle,
  type RestRecorderDeps,
} from './rest/autoprofile';

/** T17 restPathFilter：REST 自动埋点 URL 白名单谓词（原始 URL 口径）。 */
export type RestPathFilter = (url: string) => boolean;

export interface NlObserveOptions {
  serviceName?: string;
  customerId?: string;
  /**
   * T17：OTLP/HTTP 接收端点（traces 口径；metrics 端点按 /v1/metrics 派生）。
   * 缺省不挂导出器——单测/离线形态，与 T01 POC 口径一致。
   * `otlpUrl` 为向后兼容别名（T13-T16 宿主既有配置），两者同传时 endpoint 优先。
   */
  endpoint?: string;
  /** 向后兼容别名：OTLP/HTTP 端点（新代码请用 endpoint） */
  otlpUrl?: string;
  /** T17：OTLP/HTTP 导出请求超时（fetch 回退路径 AbortSignal；缺省 5000ms） */
  exportTimeoutMs?: number;
  /** T17：REST 自动埋点 URL 白名单（缺省=全部非排除 URL；trackRest 手动兜底不受限） */
  restPathFilter?: RestPathFilter;
  /** BatchSpanProcessor scheduledDelayMillis；详设 §1.5 batchTimeout 默认 2000ms 可配 */
  batchTimeoutMs?: number;
  /** 页面卸载 forceFlush 超时（详设 §4.2 默认 2000ms 可配） */
  flushTimeoutMs?: number;
  /** 注入 window（缺省全局探测；node/SSR → null 即不挂 flush/load 监听——单测注入 fake） */
  windowLike?: WindowLike | null;
  /** 注入 PerformanceObserver 构造器（缺省全局探测；显式 null = 禁用 LCP 走 load 兜底） */
  performanceObserver?: LcpObserverCtor | null;
  /** load 兜底首屏耗时取值（缺省 navigation loadEventEnd / performance.now()） */
  loadTimeMs?: () => number;
  /** 附加 SpanProcessor（宿主扩展点；单测注入 SimpleSpanProcessor+InMemorySpanExporter） */
  spanProcessors?: SpanProcessor[];
  /** 附加 MetricReader（宿主扩展点；单测注入 InMemory 读取器断言 data point） */
  metricReaders?: IMetricReader[];
  /** 可注入时钟（缺省 Date.now；单测注入后有效耗时为确定性断言） */
  clock?: () => number;
  /** REST 自动埋点（fetch/XHR 全局包装）开关，缺省 true；trackRest 手动兜底不受此开关影响 */
  instrumentRest?: boolean;
}

export interface ResolvedOptions {
  serviceName: string;
  customerId: string;
  /** 解析后的 OTLP/HTTP 端点（endpoint ?? otlpUrl；undefined = 离线不导出） */
  endpoint?: string;
  batchTimeoutMs: number;
}

export const DEFAULT_BATCH_TIMEOUT_MS = 2000;
export const DEFAULT_EXPORT_TIMEOUT_MS = 5000;

export class NlObserveClient {
  isInitialized = false;
  resolvedOptions: ResolvedOptions = {
    serviceName: '',
    customerId: '',
    batchTimeoutMs: DEFAULT_BATCH_TIMEOUT_MS,
  };

  private provider?: WebTracerProvider;
  private tracer?: Tracer;
  private meterProvider?: MeterProvider;
  private readonly signin = new SigninManager();
  private business?: BusinessManager;
  private dropCounting?: DropCountingSpanExporter;
  private beaconMetricExporter?: SendBeaconMetricExporter;
  private flushHook?: FlushHook;
  private firstScreen?: FirstScreenLoadTracker;
  private restDeps?: RestRecorderDeps;
  private restHandle?: RestInstrumentationHandle;

  /** 装配 OTel provider/tracer/meter 与状态机（幂等：重复 init 返回并 warn）。 */
  init(options: NlObserveOptions = {}): void {
    if (this.isInitialized) {
      console.warn('[nl-observe] init called on initialized client; ignored');
      return;
    }
    // T17：endpoint 为规范配置名，otlpUrl 为向后兼容别名（同传 endpoint 优先）
    const endpoint = options.endpoint ?? options.otlpUrl;
    this.resolvedOptions = {
      serviceName: options.serviceName ?? 'unknown-service',
      customerId: options.customerId ?? '',
      endpoint,
      batchTimeoutMs: options.batchTimeoutMs ?? DEFAULT_BATCH_TIMEOUT_MS,
    };

    const processors: SpanProcessor[] = [...(options.spanProcessors ?? [])];
    const readers: IMetricReader[] = [...(options.metricReaders ?? [])];
    if (this.resolvedOptions.endpoint) {
      // T15：OTLP/HTTP JSON 文本口径（sendBeacon 仅支持文本）——trace/metric 共享 transport；
      // BSP 内建 document-hide 自动 flush 关闭，flush 语义统一由 flush hook 拥有
      // （避免双 beacon，且每次 flush 都尽力上报 nl.sdk.flush）。
      // T17：exportTimeoutMs 作用于 fetch 回退路径；SDK 导出失败日志经 diag 落 console.debug
      // （静默降级，NFR-03）。
      const transport = createOtlpHttpBeaconTransport(
        options.exportTimeoutMs ?? DEFAULT_EXPORT_TIMEOUT_MS,
      );
      installSilentDiagnostics();
      this.dropCounting = new DropCountingSpanExporter(
        new SendBeaconSpanExporter({ url: this.resolvedOptions.endpoint, transport }),
      );
      processors.push(
        new BatchSpanProcessor(this.dropCounting, {
          scheduledDelayMillis: this.resolvedOptions.batchTimeoutMs,
          disableAutoFlushOnDocumentHide: true,
        }),
      );
      this.beaconMetricExporter = new SendBeaconMetricExporter({
        url: metricsUrlFromTracesUrl(this.resolvedOptions.endpoint),
        transport,
      });
      readers.push(
        new PeriodicExportingMetricReader({
          exporter: this.beaconMetricExporter,
          exportIntervalMillis: this.resolvedOptions.batchTimeoutMs,
        }),
      );
    }

    // OTel JS 2.x：Resource 经 resourceFromAttributes；spanProcessors 经构造器传入（poc-notes §2）
    const resource = resourceFromAttributes({
      'service.name': this.resolvedOptions.serviceName,
      'nl.customer_id': this.resolvedOptions.customerId,
    });
    this.provider = new WebTracerProvider({
      resource,
      spanProcessors: processors,
    });
    // 不 provider.register()（全局注册一次性，POC 发现 4）：显式取 tracer
    this.tracer = this.provider.getTracer('nl-observe-sdk-web', SDK_VERSION);

    // T13/T15：MeterProvider（otlpUrl 时挂 SendBeaconMetricReader；其余 readers 为扩展/测试注入点）
    this.meterProvider = new MeterProvider({ resource, readers });
    // nl.business_duration 用 Counter（sum 形态）：跨层契约 backend OtlpMetricPayload
    // "sum 形态（nl.business_duration / nl.sdk.flush）"；同步 Gauge 按属性集 last-value-wins
    // 会致同码业务折叠 + cumulative 重发重复行（T13 评审 Major-1）。
    // data point 属性含 biz.trace_id（每业务唯一时序键）；T15/T17 接线导出时用 DELTA。
    const businessDurationCounter = this.meterProvider
      .getMeter('nl-observe-sdk-web', SDK_VERSION)
      .createCounter(METRIC_BUSINESS_DURATION, {
        unit: 'ms',
        description: '业务有效耗时（ms）= 墙钟 - Σ挂起，每业务一个 data point（sum 形态）',
      });

    this.business = new BusinessManager({
      tracer: this.tracer,
      isSignedIn: () => this.signin.isSignedIn(),
      getCustomerId: () => this.resolvedOptions.customerId,
      now: options.clock,
      emitBusinessDuration: (valueMs, attrs) => businessDurationCounter.add(valueMs, attrs),
    });

    // T15 首屏：nl.first_screen_load Counter（sum+DELTA，与 T13 口径一致）——每页仅一个 data point，
    // LCP 优先（PerformanceObserver buffered 首批末条），无 LCP 能力时 load 事件兜底。
    const firstScreenLoadCounter = this.meterProvider
      .getMeter('nl-observe-sdk-web', SDK_VERSION)
      .createCounter(METRIC_FIRST_SCREEN_LOAD, {
        unit: 'ms',
        description: '首屏加载（ms）：LCP 优先、load 事件兜底，每页仅一个 data point',
      });
    this.firstScreen = trackFirstScreenLoad({
      emit: (valueMs) => firstScreenLoadCounter.add(valueMs),
      performanceObserver: options.performanceObserver,
      windowLike: options.windowLike,
      loadTimeMs: options.loadTimeMs,
    });

    // T15 flush：pagehide / visibilitychange=hidden → forceFlush（sendBeacon OTLP/HTTP JSON），
    // 每次尝试尽力上报 nl.sdk.flush{flush=beacon, result=ok|fail|timeout, dropped_spans=N}；
    // flush 指标自身再导出失败（二次失败）仅 console.warn，绝不抛错（详设 §4.2）。
    const flushCounter = this.meterProvider
      .getMeter('nl-observe-sdk-web', SDK_VERSION)
      .createCounter(METRIC_SDK_FLUSH, {
        unit: '1',
        description: 'flush 尝试结果：result=ok|fail|timeout，dropped_spans=随页面丢失的 span 数（每次尝试一个 data point）',
      });
    this.flushHook = setupFlushHook({
      forceFlushAll: () =>
        Promise.all([this.provider?.forceFlush(), this.meterProvider?.forceFlush()]).then(() => undefined),
      getDroppedSpans: () => this.dropCounting?.getDropped() ?? 0,
      reportFlush: (result, droppedSpans) =>
        flushCounter.add(1, {
          [ATTR_FLUSH]: FLUSH_MODE_BEACON,
          [ATTR_FLUSH_RESULT]: result,
          [ATTR_DROPPED_SPANS]: droppedSpans,
        }),
      flushAgain: () => this.meterProvider?.forceFlush() ?? Promise.resolve(),
      getExportError: () => this.beaconMetricExporter?.getLastError(),
      resetExportError: () => this.beaconMetricExporter?.resetLastError(),
      flushTimeoutMs: options.flushTimeoutMs,
      windowLike: options.windowLike,
    });

    // T14：REST 埋点依赖（自动埋点与 trackRest 共用）；parent 取当前 ACTIVE 业务 root span，
    // OTLP 自身端点自排除（防自埋点递归）
    this.restDeps = {
      getTracer: () => this.tracer,
      getActiveRootSpan: () => this.business?.activeRootSpan(),
      now: options.clock,
      isExcludedUrl: this.resolvedOptions.endpoint
        ? (url: string) => url.startsWith(this.resolvedOptions.endpoint as string)
        : undefined,
      // T17 restPathFilter：自动埋点 URL 白名单（缺省全放行；trackRest 不受限）
      restPathFilter: options.restPathFilter,
    };
    if (options.instrumentRest !== false) {
      this.restHandle = installRestInstrumentation(this.restDeps);
    }
    this.isInitialized = true;
  }

  /** 供业务状态机/后续 T14 REST 埋点取用（显式 tracer，不全局 register）。 */
  getTracer(): Tracer | undefined {
    return this.tracer;
  }

  /**
   * 关闭：先卸载 REST 全局包装与摘监听（flush hook / 首屏跟踪）——防 flush 期间新请求
   * 再生 span——再 flush provider/meterProvider 剩余 span/metric。
   * T17 降级策略：OTel 2.x BSP.shutdown 在导出失败（endpoint 不可达）时 reject——
   * shutdown 是 best-effort 收尾，吞错并 console.debug，绝不把导出失败抛回业务层。
   */
  async shutdown(): Promise<void> {
    this.restHandle?.uninstall();
    this.restHandle = undefined;
    this.flushHook?.dispose();
    this.flushHook = undefined;
    this.firstScreen?.dispose();
    this.firstScreen = undefined;
    try {
      await this.provider?.shutdown();
    } catch (err) {
      console.debug('[nl-observe] provider shutdown degraded (export failed):', err);
    }
    try {
      await this.meterProvider?.shutdown();
    } catch (err) {
      console.debug('[nl-observe] meterProvider shutdown degraded (export failed):', err);
    }
    this.isInitialized = false;
  }

  // ---- 门面方法（T13 语义：未 init no-op+warn；签入互斥与业务状态机见 SigninManager/BusinessManager） ----

  /** 签入（互斥：已签入再签入先隐式 sign_out 并 warn）；更新 customerId。
   *  customerId 长度上限 128（安全评审 F2：防超长串放大 OTLP 载荷）。 */
  signIn(customerId: string): void {
    if (!this.guard('signIn')) return;
    if (customerId.length > 128) {
      console.warn('[nl-observe] signIn: customerId too long (max 128); no-op');
      return;
    }
    this.signin.signIn();
    this.resolvedOptions = { ...this.resolvedOptions, customerId };
  }

  /** 签出：后续 startBusiness no-op+warn。 */
  signOut(): void {
    if (!this.guard('signOut')) return;
    this.signin.signOut();
  }

  isSignedIn(): boolean {
    return this.signin.isSignedIn();
  }

  /**
   * 起业务 root span（biz.business.<bizCode>，独立 traceId），自动挂起其他 ACTIVE 业务；
   * 未签入 → no-op+warn。@returns bizId（未签入时 undefined）
   */
  startBusiness(bizCode: string): string | undefined {
    if (!this.guard('startBusiness')) return undefined;
    return this.business?.start(bizCode);
  }

  /** 挂起业务（bizId 或 bizCode；缺省=当前 ACTIVE）。 */
  suspendBusiness(bizId?: string): void {
    if (!this.guard('suspendBusiness')) return;
    this.business?.suspend(bizId);
  }

  /** 恢复业务（记 biz.resume event，累计挂起时长）。 */
  resumeBusiness(bizId: string): void {
    if (!this.guard('resumeBusiness')) return;
    this.business?.resume(bizId);
  }

  /**
   * 结束业务：status 为成败唯一依据（success|fail），缺省 fail
   * （"未显式结束计 fail" 缺省路径在 BusinessManager.end，评审 n2：缺省只留一处）；
   * endReason 缺省 explicit，unmount 未显式结束由适配层传 endReason:'unmount'。
   */
  endBusiness(
    bizId: string,
    options: { status?: BizStatus; endReason?: BizEndReason } = {},
  ): void {
    if (!this.guard('endBusiness')) return;
    this.business?.end(bizId, options);
  }

  /**
   * 查业务实例状态（ACTIVE/SUSPENDED；ENDED 或未知 → undefined，已结束实例已移出实例表）。
   * T16 指令 unmount 兜底用：仅未显式结束的业务才以 fail+unmount 结束，不覆盖 explicit 口径。
   */
  businessState(bizId: string): BusinessState | undefined {
    if (!this.guard('businessState')) return undefined;
    return this.business?.stateOf(bizId);
  }

  /** 当前 ACTIVE 业务（bizId + root span；至多一个——T13 Major-2 不变量；无则 undefined）。
   *  T16 v-span 指令取子 span 父（与 T14 REST 埋点取父同规则）。 */
  activeBusiness(): ActiveBusiness | undefined {
    if (!this.guard('activeBusiness')) return undefined;
    return this.business?.getActive();
  }

  /**
   * 手动 REST 兜底（T14）：与自动埋点共享 recordRestSpan——同命名（'HTTP {METHOD}'）、
   * 同取父（当前 ACTIVE 业务 root span，无 ACTIVE 不上报）、同白名单 4 属性；
   * route 同样去 query。micro-app jsSandbox 下自动埋点捕获不足时的兜底路径（T21）。
   */
  trackRest(method: string, route: string, statusCode: number, durationMs: number): void {
    if (!this.guard('trackRest')) return;
    if (!this.restDeps) return; // init 未装配 REST 依赖（理论不可达，防御）
    recordRestSpan(this.restDeps, { method, route, statusCode, durationMs });
  }

  private guard(api: string): boolean {
    if (!this.isInitialized) {
      console.warn(`[nl-observe] ${api} called before init; no-op`);
      return false;
    }
    return true;
  }
}

/**
 * 工厂 + 单例守卫（详设 §5.1）：重复调用返回既有实例并 warn。
 * 直接 `new NlObserveClient()` 不经守卫（测试可构造未 init 实例）。
 */
export function createClient(options: NlObserveOptions = {}): NlObserveClient {
  const existing = getGuardedClient();
  if (existing) {
    console.warn('[nl-observe] createClient called again; returning existing instance (single-init guard)');
    return existing.instance;
  }
  const client = new NlObserveClient();
  client.init(options);
  setGuardedClient({ version: SDK_VERSION, instance: client });
  return client;
}

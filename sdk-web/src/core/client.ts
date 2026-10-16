/**
 * NlObserveClient 门面（详设 §1.5 core/client.ts）。
 *
 * init 装配 OTel WebTracerProvider + MeterProvider（版本按 poc-notes §2 锁定，API 按
 * 「OTel JS 2.x 变化」四条编写：resourceFromAttributes / spanProcessors 构造器传入 /
 * 不全局 register——POC 发现 4，tracer 经 provider.getTracer 显式获取）。
 * T13 落地：signIn/signOut 经 SigninManager 状态机（互斥：已签入再签入先隐式 sign_out）；
 * startBusiness/suspendBusiness/resumeBusiness/endBusiness 经 BusinessManager
 * （挂起/恢复/有效耗时，end 发 nl.business_duration data point）。
 * T16 落地：businessState/activeBusiness 供 vue 指令（v-biz unmount 兜底、v-span 取父）查询。
 * REST 自动埋点在 T14、首屏/flush 指标在 T15。
 */
import type { Tracer } from '@opentelemetry/api';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { BatchSpanProcessor, type SpanProcessor } from '@opentelemetry/sdk-trace-base';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { MeterProvider, type IMetricReader } from '@opentelemetry/sdk-metrics';
import { SDK_VERSION } from './version';
import { getGuardedClient, setGuardedClient } from './guard';
import { SigninManager } from './SigninManager';
import {
  BusinessManager,
  METRIC_BUSINESS_DURATION,
  type ActiveBusiness,
  type BizEndReason,
  type BizStatus,
  type BusinessState,
} from './BusinessManager';

export interface NlObserveOptions {
  serviceName?: string;
  customerId?: string;
  /** OTLP/HTTP 接收端点（缺省不挂导出器——单测/离线形态，与 T01 POC 口径一致） */
  otlpUrl?: string;
  /** BatchSpanProcessor scheduledDelayMillis；详设 §1.5 batchTimeout 默认 2000ms 可配 */
  batchTimeoutMs?: number;
  /** 附加 SpanProcessor（宿主扩展点；单测注入 SimpleSpanProcessor+InMemorySpanExporter） */
  spanProcessors?: SpanProcessor[];
  /** 附加 MetricReader（宿主扩展点；单测注入 InMemory 读取器断言 data point） */
  metricReaders?: IMetricReader[];
  /** 可注入时钟（缺省 Date.now；单测注入后有效耗时为确定性断言） */
  clock?: () => number;
}

export interface ResolvedOptions {
  serviceName: string;
  customerId: string;
  otlpUrl?: string;
  batchTimeoutMs: number;
}

export const DEFAULT_BATCH_TIMEOUT_MS = 2000;

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

  /** 装配 OTel provider/tracer/meter 与状态机（幂等：重复 init 返回并 warn）。 */
  init(options: NlObserveOptions = {}): void {
    if (this.isInitialized) {
      console.warn('[nl-observe] init called on initialized client; ignored');
      return;
    }
    this.resolvedOptions = {
      serviceName: options.serviceName ?? 'unknown-service',
      customerId: options.customerId ?? '',
      otlpUrl: options.otlpUrl,
      batchTimeoutMs: options.batchTimeoutMs ?? DEFAULT_BATCH_TIMEOUT_MS,
    };

    const processors: SpanProcessor[] = [...(options.spanProcessors ?? [])];
    if (this.resolvedOptions.otlpUrl) {
      processors.push(
        new BatchSpanProcessor(new OTLPTraceExporter({ url: this.resolvedOptions.otlpUrl }), {
          scheduledDelayMillis: this.resolvedOptions.batchTimeoutMs,
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

    // T13：MeterProvider（OTLP 指标导出器在 T15/T17 接线；此处 readers 为扩展/测试注入点）
    this.meterProvider = new MeterProvider({
      resource,
      readers: options.metricReaders ?? [],
    });
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
    this.isInitialized = true;
  }

  /** 供业务状态机/后续 T14 REST 埋点取用（显式 tracer，不全局 register）。 */
  getTracer(): Tracer | undefined {
    return this.tracer;
  }

  /** 关闭 provider/meterProvider（flush 剩余 span/metric）。 */
  async shutdown(): Promise<void> {
    await this.provider?.shutdown();
    await this.meterProvider?.shutdown();
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

  trackRest(method: string, route: string, statusCode: number, durationMs: number): void {
    if (!this.guard('trackRest')) return;
    void method;
    void route;
    void statusCode;
    void durationMs; // T14
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

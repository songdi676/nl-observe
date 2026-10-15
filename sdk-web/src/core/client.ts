/**
 * NlObserveClient 门面（详设 §1.5 core/client.ts）。
 *
 * T12 骨架语义：init 装配 OTel WebTracerProvider（版本按 poc-notes §2 锁定，API 按
 * 「OTel JS 2.x 变化」四条编写：resourceFromAttributes / spanProcessors 构造器传入 /
 * 不全局 register——POC 发现 4，tracer 经 provider.getTracer 显式获取）。
 * 业务生命周期方法（signIn/startBusiness/…）为 no-op 占位，状态机与 span 语义在 T13 落地；
 * REST 埋点在 T14、指标与 flush 在 T15。
 */
import type { Tracer } from '@opentelemetry/api';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { BatchSpanProcessor, type SpanProcessor } from '@opentelemetry/sdk-trace-base';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { SDK_VERSION } from './version';
import { getGuardedClient, setGuardedClient } from './guard';

export interface NlObserveOptions {
  serviceName?: string;
  customerId?: string;
  /** OTLP/HTTP 接收端点（缺省不挂导出器——骨架/单测形态，与 T01 POC 口径一致） */
  otlpUrl?: string;
  /** BatchSpanProcessor scheduledDelayMillis；详设 §1.5 batchTimeout 默认 2000ms 可配 */
  batchTimeoutMs?: number;
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

  /** 装配 OTel provider/tracer（幂等：重复 init 返回并 warn）。 */
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

    const processors: SpanProcessor[] = [];
    if (this.resolvedOptions.otlpUrl) {
      processors.push(
        new BatchSpanProcessor(new OTLPTraceExporter({ url: this.resolvedOptions.otlpUrl }), {
          scheduledDelayMillis: this.resolvedOptions.batchTimeoutMs,
        }),
      );
    }

    // OTel JS 2.x：Resource 经 resourceFromAttributes；spanProcessors 经构造器传入（poc-notes §2）
    this.provider = new WebTracerProvider({
      resource: resourceFromAttributes({
        'service.name': this.resolvedOptions.serviceName,
        'nl.customer_id': this.resolvedOptions.customerId,
      }),
      spanProcessors: processors,
    });
    // 不 provider.register()（全局注册一次性，POC 发现 4）：显式取 tracer
    this.tracer = this.provider.getTracer('nl-observe-sdk-web', SDK_VERSION);
    this.isInitialized = true;
  }

  /** 供 T13 业务状态机取用（骨架阶段仅暴露，不产生 span）。 */
  getTracer(): Tracer | undefined {
    return this.tracer;
  }

  /** 关闭 provider（flush 剩余 span）。 */
  async shutdown(): Promise<void> {
    await this.provider?.shutdown();
    this.isInitialized = false;
  }

  // ---- 门面方法（T13 前骨架语义：未 init no-op+warn；已 init 仅记录，状态机在 T13 落地） ----

  signIn(customerId: string): void {
    if (!this.guard('signIn')) return;
    this.resolvedOptions = { ...this.resolvedOptions, customerId };
  }

  signOut(): void {
    if (!this.guard('signOut')) return;
  }

  startBusiness(bizCode: string): void {
    if (!this.guard('startBusiness')) return;
    void bizCode; // T13：root span biz.business.<bizCode>
  }

  suspendBusiness(bizCode: string): void {
    if (!this.guard('suspendBusiness')) return;
    void bizCode; // T13
  }

  resumeBusiness(bizCode: string): void {
    if (!this.guard('resumeBusiness')) return;
    void bizCode; // T13
  }

  endBusiness(bizCode: string, endReason?: string): void {
    if (!this.guard('endBusiness')) return;
    void bizCode;
    void endReason; // T13
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

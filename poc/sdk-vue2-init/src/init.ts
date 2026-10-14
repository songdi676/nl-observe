/**
 * T01 POC（vue2 宿主）：验证 OTel 前端 SDK 选型可初始化并可导出 span。
 * （与 sdk-vue3-init/src/init.ts 相同——POC 阶段双宿主共享同一 core 形态，T12 正式化。）
 *
 * 选型结论（详见 poc/poc-notes.md）：
 * - npm 上不存在 @opentelemetry/sdk-web（registry 404），替代为 @opentelemetry/sdk-trace-web。
 * - provider 用 WebTracerProvider，span 经 InMemorySpanExporter 断言；
 *   OTLPTraceExporter（@opentelemetry/exporter-trace-otlp-http）仅验证可构造与可挂载
 *   （真实 4318 端点由 poc/receiver-otlp-kafka 覆盖）。
 * - POC 发现：OTel JS 2.x 全局注册一次性（register 后不可替换），
 *   故 POC 用 provider.getTracer 显式取 tracer；正式 SDK 以 window.__NL_OBSERVE__ 单例守卫（详设 §5.1）。
 */
import { ROOT_CONTEXT, type Tracer } from '@opentelemetry/api';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import {
  BatchSpanProcessor,
  InMemorySpanExporter,
  SimpleSpanProcessor,
  type SpanProcessor,
} from '@opentelemetry/sdk-trace-base';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
// OTel JS 2.x：Resource 构造器已移除，改用 resourceFromAttributes（POC 发现，见 poc-notes.md）
import { resourceFromAttributes } from '@opentelemetry/resources';

export interface PocInitOptions {
  serviceName: string;
  customerId: string;
  /** OTLP/HTTP 接收端点；缺省仅用内存导出（单测） */
  otlpUrl?: string;
}

export interface PocHandles {
  provider: WebTracerProvider;
  tracer: Tracer;
  memoryExporter: InMemorySpanExporter;
  otlpExporter?: OTLPTraceExporter;
}

/** 初始化 WebTracerProvider（resource 带 nl.customer_id，与概要设计 §7 一致）。 */
export function initPocTracing(opts: PocInitOptions): PocHandles {
  const memoryExporter = new InMemorySpanExporter();
  const processors: SpanProcessor[] = [new SimpleSpanProcessor(memoryExporter)];

  let otlpExporter: OTLPTraceExporter | undefined;
  if (opts.otlpUrl) {
    otlpExporter = new OTLPTraceExporter({ url: opts.otlpUrl });
    processors.push(new BatchSpanProcessor(otlpExporter, {
      scheduledDelayMillis: 2000, // 详设 §1.5 batchTimeout 默认 2000ms
    }));
  }

  // OTel JS 2.x：spanProcessors 经构造器传入（addSpanProcessor 已移除，POC 发现见 poc-notes.md）
  const provider = new WebTracerProvider({
    resource: resourceFromAttributes({
      'service.name': opts.serviceName,
      'nl.customer_id': opts.customerId,
    }),
    spanProcessors: processors,
  });

  return {
    provider,
    tracer: provider.getTracer('nl-observe-poc', '0.0.0'),
    memoryExporter,
    otlpExporter,
  };
}

/**
 * 以 ROOT_CONTEXT 为父生成业务 root span（详设 §1.5：保证不同业务不同 traceId）。
 * 返回 span，调用方负责 end()。
 */
export function startBusinessRootSpan(tracer: Tracer, bizCode: string) {
  return tracer.startSpan(`biz.business.${bizCode}`, {}, ROOT_CONTEXT);
}

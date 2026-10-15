/**
 * T13 core 单测：SigninManager + BusinessManager 状态机（挂起/恢复/有效耗时）。
 *
 * 覆盖任务测试用例：
 * 1. 签入互斥：已签入再签入先隐式 sign_out（warn）；sign_out 后 startBusiness no-op+warn
 * 2. A、B 双业务 traceId 不同；B 启动后 A 有 biz.suspend_start 且 A root span 未 end
 * 3. 挂起 100ms 后恢复，effective_duration_ms = 墙钟-100ms（注入时钟，确定性断言）
 * 4. endBusiness fail → biz.status=fail；unmount 未 end → end_reason=unmount 且 status=fail
 * 另：end 发 nl.business_duration data point（InMemory 指标读取断言 value 与 attributes）。
 *
 * node 环境（WebTracerProvider/MeterProvider 构造器不触碰 window；不全局 register——POC 发现 4）。
 * span 捕获：SimpleSpanProcessor+InMemorySpanExporter 断言已结束 span；
 * 自写捕获 processor 的 onStart 拿活 Span（OTel JS 2.x：活 Span 即 ReadableSpan 形态，
 * events/attributes 实时可读）断言未结束（挂起中）span 的 event 与 ended 状态。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { type ReadableSpan, type Span, type SpanProcessor } from '@opentelemetry/sdk-trace-base';
import {
  AggregationTemporality,
  DataPointType,
  InMemoryMetricExporter,
  PeriodicExportingMetricReader,
  type DataPoint,
  type MetricData,
} from '@opentelemetry/sdk-metrics';
import { InMemorySpanExporter, SimpleSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { NlObserveClient } from '../src/core/client';
import { METRIC_BUSINESS_DURATION } from '../src/core/BusinessManager';

interface Captured {
  started: Map<string, Span>;
  finished: ReadableSpan[];
  processor: SpanProcessor;
}

/** 捕获 onStart（活 Span，可实时读 events/attributes）与 onEnd 的测试 processor。 */
function makeCaptureProcessor(): Captured {
  const started = new Map<string, Span>();
  const finished: ReadableSpan[] = [];
  const processor: SpanProcessor = {
    onStart: (span) => {
      started.set(span.name, span);
    },
    onEnd: (span) => {
      finished.push(span);
    },
    shutdown: () => Promise.resolve(),
    forceFlush: () => Promise.resolve(),
  };
  return { started, finished, processor };
}

interface Harness {
  client: NlObserveClient;
  spans: InMemorySpanExporter;
  cap: Captured;
  reader?: PeriodicExportingMetricReader;
  metricsExporter?: InMemoryMetricExporter;
}

function makeClient(opts: { clock?: () => number; withMetrics?: boolean } = {}): Harness {
  const spans = new InMemorySpanExporter();
  const cap = makeCaptureProcessor();
  let reader: PeriodicExportingMetricReader | undefined;
  let metricsExporter: InMemoryMetricExporter | undefined;
  if (opts.withMetrics) {
    // 同步 Gauge 仅支持 cumulative 时序（OTel 指标语义规范）
    metricsExporter = new InMemoryMetricExporter(AggregationTemporality.CUMULATIVE);
    reader = new PeriodicExportingMetricReader({
      exporter: metricsExporter,
      exportIntervalMillis: 3_600_000,
    });
  }
  const client = new NlObserveClient();
  client.init({
    serviceName: 'test',
    customerId: 'C-1',
    clock: opts.clock,
    spanProcessors: [new SimpleSpanProcessor(spans), cap.processor],
    metricReaders: reader ? [reader] : undefined,
  });
  return { client, spans, cap, reader, metricsExporter };
}

function eventNames(span: ReadableSpan): string[] {
  return span.events.map((e) => e.name);
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('T13 签入互斥（SigninManager）', () => {
  it('已签入再签入：先隐式 sign_out（warn）再签入，后续 startBusiness 正常', () => {
    const { client, cap } = makeClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    client.signIn('C-1');
    client.signIn('C-2'); // 已签入 → 隐式 sign_out + warn
    expect(
      warn.mock.calls.some((c) => String(c[0]).includes('sign_in')),
    ).toBe(true);
    expect(client.resolvedOptions.customerId).toBe('C-2');

    client.startBusiness('ORDER'); // 隐式重签入后应正常建 span
    expect([...cap.started.keys()]).toEqual(['biz.business.ORDER']);
  });

  it('sign_out 后 startBusiness：no-op + warn（不产生 span）', () => {
    const { client, cap } = makeClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    client.signIn('C-1');
    client.startBusiness('ORDER');
    client.signOut();
    client.startBusiness('AGAIN'); // 未签入 → no-op + warn
    expect(
      warn.mock.calls.some((c) => String(c[0]).includes('startBusiness')),
    ).toBe(true);
    expect([...cap.started.keys()]).toEqual(['biz.business.ORDER']);
  });
});

describe('T13 双业务独立 trace 与自动挂起（BusinessManager）', () => {
  it('A、B 双业务 root span traceId 不同；B 启动后 A 记 biz.suspend_start 且 A root span 未 end', () => {
    const { client, cap } = makeClient();
    client.signIn('C-1');
    client.startBusiness('A');
    client.startBusiness('B');

    const a = cap.started.get('biz.business.A');
    const b = cap.started.get('biz.business.B');
    expect(a).toBeDefined();
    expect(b).toBeDefined();
    // 不同业务 → 不同 traceId（ROOT_CONTEXT 独立 root）
    expect(a!.spanContext().traceId).toMatch(/^[0-9a-f]{32}$/);
    expect(b!.spanContext().traceId).toMatch(/^[0-9a-f]{32}$/);
    expect(a!.spanContext().traceId).not.toBe(b!.spanContext().traceId);
    // B 启动自动挂起 A：A 有 biz.suspend_start，span 不结束（ended=false）
    expect(eventNames(a!)).toContain('biz.suspend_start');
    expect(a!.ended).toBe(false);
    // B 为当前 ACTIVE：无挂起事件、无结束
    expect(eventNames(b!)).not.toContain('biz.suspend_start');
    expect(b!.ended).toBe(false);
  });
});

describe('T13 有效耗时（挂起 100ms，注入时钟确定性断言）', () => {
  it('挂起 100ms 后恢复：effective_duration_ms = 墙钟-Σ挂起；total_suspended_ms=100', () => {
    let t = 1_000_000;
    const { client, spans, cap } = makeClient({ clock: () => t });
    client.signIn('C-1');
    client.startBusiness('A'); // t0
    t += 50;
    client.suspendBusiness('A'); // 挂起起点 t0+50
    t += 100;
    client.resumeBusiness('A'); // 恢复 t0+150 → Σ挂起=100（记 biz.resume event）
    t += 200;
    client.endBusiness('A', { status: 'success' }); // 墙钟 350ms

    const finished = spans.getFinishedSpans().find((s) => s.name === 'biz.business.A');
    expect(finished).toBeDefined();
    expect(finished!.attributes['biz.total_suspended_ms']).toBe(100);
    expect(finished!.attributes['biz.effective_duration_ms']).toBe(250); // 350 - 100
    expect(finished!.attributes['biz.status']).toBe('success');
    expect(finished!.attributes['biz.end_reason']).toBe('explicit');
    // 恢复动作记 biz.resume event（挂起/恢复均为 span event，span 不结束）
    const live = cap.started.get('biz.business.A');
    expect(eventNames(live!)).toContain('biz.resume');
    expect(eventNames(live!)).toContain('biz.suspend_start');
  });
});

describe('T13 成败口径（endBusiness 唯一依据）', () => {
  it('endBusiness({status:"fail"}) → biz.status=fail、end_reason=explicit', () => {
    const { client, spans } = makeClient();
    client.signIn('C-1');
    client.startBusiness('A');
    client.endBusiness('A', { status: 'fail' });

    const finished = spans.getFinishedSpans().find((s) => s.name === 'biz.business.A');
    expect(finished!.attributes['biz.status']).toBe('fail');
    expect(finished!.attributes['biz.end_reason']).toBe('explicit');
  });

  it('unmount 未显式结束计 fail：endReason=unmount → biz.end_reason=unmount 且 biz.status=fail', () => {
    const { client, spans } = makeClient();
    client.signIn('C-1');
    client.startBusiness('B');
    client.endBusiness('B', { status: 'fail', endReason: 'unmount' });

    const finished = spans.getFinishedSpans().find((s) => s.name === 'biz.business.B');
    expect(finished!.attributes['biz.status']).toBe('fail');
    expect(finished!.attributes['biz.end_reason']).toBe('unmount');
  });
});

describe('T13 nl.business_duration data point', () => {
  it('end 发 nl.business_duration data point：value=有效耗时，attributes 含 biz.code/biz.status/biz.end_reason', async () => {
    let t = 0;
    const { client, reader, metricsExporter } = makeClient({ clock: () => t, withMetrics: true });
    client.signIn('C-1');
    client.startBusiness('ORDER');
    t += 420;
    client.endBusiness('ORDER', { status: 'success' });

    await reader!.forceFlush(); // 周期读取器：forceFlush 触发 collect + export 到 InMemory 导出器
    const resourceMetrics = metricsExporter!.getMetrics();
    let metric: MetricData | undefined;
    for (const rm of resourceMetrics) {
      for (const sm of rm.scopeMetrics) {
        for (const m of sm.metrics) {
          if (m.descriptor.name === METRIC_BUSINESS_DURATION) {
            metric = m;
          }
        }
      }
    }
    expect(metric).toBeDefined();
    expect(metric!.dataPointType).toBe(DataPointType.GAUGE);
    expect(metric!.dataPoints.length).toBe(1);
    const dp = metric!.dataPoints[0] as DataPoint<number>;
    expect(dp.value).toBe(420);
    expect(dp.attributes).toEqual({
      'biz.code': 'ORDER',
      'biz.status': 'success',
      'biz.end_reason': 'explicit',
    });
  });
});

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
import { type Attributes } from '@opentelemetry/api';
import { type ReadableSpan, type Span, type SpanProcessor } from '@opentelemetry/sdk-trace-base';
import {
  AggregationTemporality,
  DataPointType,
  InMemoryMetricExporter,
  PeriodicExportingMetricReader,
  type DataPoint,
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
    // Counter 支持 DELTA（同步 Gauge 不支持）：DELTA 只导出新增、不重发已导出时序
    // ——钉住 Major-1 的 cumulative 重发问题（见 BusinessManager 头注释）
    metricsExporter = new InMemoryMetricExporter(AggregationTemporality.DELTA);
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

/** 收集 nl.business_duration 的全部 data point（断言 sum 形态，钉 Major-1 契约）。 */
function collectBusinessDurationDps(
  exporter: InMemoryMetricExporter,
): Array<{ value: number; attributes: Attributes }> {
  const out: Array<{ value: number; attributes: Attributes }> = [];
  for (const rm of exporter.getMetrics()) {
    for (const sm of rm.scopeMetrics) {
      for (const m of sm.metrics) {
        if (m.descriptor.name !== METRIC_BUSINESS_DURATION) continue;
        expect(m.dataPointType).toBe(DataPointType.SUM); // sum 形态（跨层契约 OtlpMetricPayload）
        for (const dp of m.dataPoints as DataPoint<number>[]) {
          out.push({ value: dp.value, attributes: dp.attributes });
        }
      }
    }
  }
  return out;
}

describe('T13 nl.business_duration data point（sum 形态 + 每业务唯一时序，Major-1 回归）', () => {
  it('end 发 sum data point：value=有效耗时，attributes 含 biz.code/status/end_reason/trace_id；' +
    '同 bizCode 两业务不折叠；DELTA 下不跨周期重发', async () => {
    let t = 0;
    const { client, reader, metricsExporter } = makeClient({ clock: () => t, withMetrics: true });
    client.signIn('C-1');
    client.startBusiness('ORDER');
    t += 420;
    client.endBusiness('ORDER', { status: 'success' });
    await reader!.forceFlush(); // 周期读取器：forceFlush 触发 collect + export

    // 同 bizCode 第二业务：traceId 不同 → 独立时序，不得被聚合折叠（旧 Gauge 实现会静默丢失）
    client.startBusiness('ORDER');
    t += 100;
    client.endBusiness('ORDER', { status: 'fail' });
    await reader!.forceFlush();

    const dps = collectBusinessDurationDps(metricsExporter!);
    expect(dps.length).toBe(2);
    expect(dps.every((dp) => dp.attributes['biz.code'] === 'ORDER')).toBe(true);
    expect(new Set(dps.map((dp) => dp.attributes['biz.trace_id'])).size).toBe(2); // 每业务唯一时序键
    expect(dps[0].value).toBe(420);
    expect(dps[0].attributes['biz.status']).toBe('success');
    expect(dps[0].attributes['biz.end_reason']).toBe('explicit');
    expect(dps[1].value).toBe(100);
    expect(dps[1].attributes['biz.status']).toBe('fail');

    // DELTA：无新增的下一周期不重发（cumulative 会每周期重发已导出时序 →
    // 后端 append 表 otel_metrics 产生重复行）
    await reader!.forceFlush();
    expect(collectBusinessDurationDps(metricsExporter!).length).toBe(2);
  });
});

describe('T13 单一 ACTIVE 不变量（Major-2 回归）', () => {
  it('start(A)→start(B)→resume(A)：A 恢复 ACTIVE，B 被自动挂起，不出现双 ACTIVE', () => {
    const { client, cap } = makeClient();
    client.signIn('C-1');
    client.startBusiness('A');
    client.startBusiness('B'); // A 被挂起（第 1 个 suspend_start）
    client.resumeBusiness('A'); // A 恢复 → B 必须自动挂起

    const a = cap.started.get('biz.business.A')!;
    const b = cap.started.get('biz.business.B')!;
    expect(eventNames(a)).toContain('biz.resume');
    expect(a.ended).toBe(false);
    // B 由 ACTIVE 转 SUSPENDED：新增 1 个 suspend_start event（B 此前未挂起），span 仍未结束
    expect(eventNames(b).filter((n) => n === 'biz.suspend_start')).toHaveLength(1);
    expect(b.ended).toBe(false);
    // A：挂起 1 次 + 恢复 1 次
    expect(eventNames(a).filter((n) => n === 'biz.suspend_start')).toHaveLength(1);
  });
});

describe('T13 边界语义（评审 m4 测试缺口补齐）', () => {
  it('挂起中结束：total_suspended_ms 补记至 end 时刻，effective = 墙钟-Σ挂起', () => {
    let t = 1_000_000;
    const { client, spans } = makeClient({ clock: () => t });
    client.signIn('C-1');
    client.startBusiness('A');
    t += 300;
    client.suspendBusiness('A');
    t += 200;
    client.endBusiness('A', { status: 'success' }); // 挂起态直接 end

    const finished = spans.getFinishedSpans().find((s) => s.name === 'biz.business.A');
    expect(finished).toBeDefined();
    expect(finished!.attributes['biz.total_suspended_ms']).toBe(200);
    expect(finished!.attributes['biz.effective_duration_ms']).toBe(300); // 500 - 200
    expect(finished!.attributes['biz.status']).toBe('success');
  });

  it('未知 key：end/suspend/resume 均 warn+no-op；重复 end 只生效一次', () => {
    const { client, spans } = makeClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    client.signIn('C-1');
    client.startBusiness('A');
    client.endBusiness('NOPE');
    client.suspendBusiness('NOPE');
    client.resumeBusiness('NOPE');
    expect(warn.mock.calls.length).toBe(3);
    client.endBusiness('A', { status: 'success' });
    client.endBusiness('A', { status: 'fail' }); // 重复 end no-op
    expect(spans.getFinishedSpans().filter((s) => s.name === 'biz.business.A')).toHaveLength(1);
  });

  it('bizCode 违反约束（含非法字符/超长）→ warn+no-op，不产生 span', () => {
    const { client, cap } = makeClient();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    client.signIn('C-1');
    client.startBusiness('order-2026-10-15-9f3k9x!'); // 含集合外字符（防高基数订单号当 bizCode）
    client.startBusiness('A'.repeat(65)); // 超 64 长度上限
    expect(warn.mock.calls.length).toBe(2);
    expect(cap.started.size).toBe(0);
  });
});

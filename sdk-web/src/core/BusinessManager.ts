/**
 * 业务实例状态机（详设 §1.5 core/BusinessManager.ts）。
 *
 * 实例表 Map<bizId, BizInstance>，状态 ACTIVE/SUSPENDED/ENDED：
 * - start(bizCode)：生成独立 root span（name=biz.business.<bizCode>，ROOT_CONTEXT → 不同业务
 *   traceId 不同），并自动挂起其他 ACTIVE 业务（span event biz.suspend_start，span 不结束）。
 * - suspend/resume：挂起/恢复仅 span event（biz.suspend_start / biz.resume）+ 累计挂起时长，
 *   span 不结束（保留 OTel 墙钟时长原义，详设 §1.5 挂起语义）。
 * - end：写 attributes biz.total_suspended_ms / biz.effective_duration_ms（=墙钟-Σ挂起）/
 *   biz.status / biz.end_reason（explicit|unmount），end span，发 nl.business_duration data point。
 *
 * 属性/事件/指标键与 backend-observe-common AttributeKeys 手工镜像（T03 已定；
 * 两侧改动必须同步，见 AttributeKeysTest 与 sdk-web 常量一致性）。
 * nl.business_duration 为 **sum 形态**（跨层契约：backend OtlpMetricPayload 明确
 * "sum 形态（nl.business_duration / nl.sdk.flush）"，T13 评审 Major-1）：
 * SDK 侧用 Counter 上报，data point 属性含 biz.trace_id（每业务唯一时序键），
 * 防同 (biz.code,status,end_reason) 的多业务在聚合内折叠。
 * 注意：T15/T17 接线 OTLP 指标导出时应选 DELTA 采集周期，避免 cumulative 每周期重发
 * 已导出时序导致 append 表（otel_metrics）重复行。
 */
import { ROOT_CONTEXT, type Span, type Tracer } from '@opentelemetry/api';

export type BusinessState = 'ACTIVE' | 'SUSPENDED' | 'ENDED';
export type BizStatus = 'success' | 'fail';
export type BizEndReason = 'explicit' | 'unmount';

/** 业务 root span 名称前缀（backend AttributeKeys.BIZ_BUSINESS_NAME_PREFIX） */
export const BIZ_BUSINESS_NAME_PREFIX = 'biz.business.';
/** span/指标属性：业务编码（AttributeKeys.BIZ_CODE） */
export const ATTR_BIZ_CODE = 'biz.code';
/** span/指标属性：业务状态 success|fail（AttributeKeys.BIZ_STATUS） */
export const ATTR_BIZ_STATUS = 'biz.status';
/** span/指标属性：结束原因 explicit|unmount（AttributeKeys.BIZ_END_REASON） */
export const ATTR_BIZ_END_REASON = 'biz.end_reason';
/** span 属性：累计挂起时长 ms（AttributeKeys.BIZ_TOTAL_SUSPENDED_MS） */
export const ATTR_BIZ_TOTAL_SUSPENDED_MS = 'biz.total_suspended_ms';
/** span 属性：有效耗时 ms = 墙钟-Σ挂起（AttributeKeys.BIZ_EFFECTIVE_DURATION_MS） */
export const ATTR_BIZ_EFFECTIVE_DURATION_MS = 'biz.effective_duration_ms';
/** span event：挂起起点（AttributeKeys.EVENT_BIZ_SUSPEND_START） */
export const EVENT_BIZ_SUSPEND_START = 'biz.suspend_start';
/** span event：恢复（AttributeKeys.EVENT_BIZ_RESUME） */
export const EVENT_BIZ_RESUME = 'biz.resume';
/** 指标：业务有效耗时（ms，每业务一个 data point；AttributeKeys.NL_BUSINESS_DURATION） */
export const METRIC_BUSINESS_DURATION = 'nl.business_duration';
/** 指标 data point 属性：业务 root span traceId（每业务唯一时序键；AttributeKeys.BIZ_TRACE_ID） */
export const ATTR_BIZ_TRACE_ID = 'biz.trace_id';

/** 指标 data point 属性集（设计 §7 + T13 评审 Major-1：加 biz.trace_id 防同码业务折叠）。 */
export type BusinessDurationAttrs = {
  [ATTR_BIZ_CODE]: string;
  [ATTR_BIZ_STATUS]: BizStatus;
  [ATTR_BIZ_END_REASON]: BizEndReason;
  [ATTR_BIZ_TRACE_ID]: string;
};

/** bizCode 输入约束（安全评审 F2：防高基数/超长串放大指标时序与 OTLP 载荷；低基数业务编码） */
export const BIZ_CODE_PATTERN = /^[A-Za-z0-9_.-]{1,64}$/;
/** 并发未结束业务实例上限（安全评审 F1：宿主缺陷循环反复 startBusiness 的 DoS 面设限） */
export const MAX_CONCURRENT_INSTANCES = 128;

/** 当前 ACTIVE 业务快照（T16 v-span 指令取子 span 父上下文用）。 */
export interface ActiveBusiness {
  bizId: string;
  span: Span;
}

export interface BusinessManagerDeps {
  tracer: Tracer;
  isSignedIn: () => boolean;
  getCustomerId: () => string;
  /** 可注入时钟（缺省 Date.now）：单测注入后 effective_duration_ms 为确定性断言 */
  now?: () => number;
  /** end 时发 nl.business_duration data point（client 接 Gauge；缺省 no-op） */
  emitBusinessDuration?: (valueMs: number, attrs: BusinessDurationAttrs) => void;
}

interface BizInstance {
  bizId: string;
  bizCode: string;
  state: BusinessState;
  span: Span;
  startMs: number;
  suspendedMs: number;
  suspendStartMs?: number;
}

export class BusinessManager {
  private readonly instances = new Map<string, BizInstance>();
  private seq = 0;

  constructor(private readonly deps: BusinessManagerDeps) {}

  private now(): number {
    return this.deps.now ? this.deps.now() : Date.now();
  }

  /** 按 key 查实例：bizId 精确匹配优先，否则取同 bizCode 最近一个未结束实例。
   *  同 bizCode 多实例时 warn 提示应传 bizId（评审 m1：避免静默命中非预期实例）。 */
  findByKey(key: string): BizInstance | undefined {
    const byId = this.instances.get(key);
    if (byId) return byId;
    let latest: BizInstance | undefined;
    let matches = 0;
    for (const inst of this.instances.values()) {
      if (inst.bizCode === key && inst.state !== 'ENDED') {
        matches += 1;
        if (!latest || inst.startMs >= latest.startMs) latest = inst;
      }
    }
    if (matches > 1) {
      console.warn(`[nl-observe] multiple non-ended instances for bizCode "${key}"; pass bizId`);
    }
    return latest;
  }

  stateOf(key: string): BusinessState | undefined {
    return this.findByKey(key)?.state;
  }

  /** 当前 ACTIVE 业务（至多一个——T13 Major-2 不变量；无则 undefined）。
   *  T16 v-span 指令以此为子 span 父（与 T14 REST 埋点取父同规则）。 */
  getActive(): ActiveBusiness | undefined {
    for (const inst of this.instances.values()) {
      if (inst.state === 'ACTIVE') return { bizId: inst.bizId, span: inst.span };
    }
    return undefined;
  }

  /**
   * 起业务：独立 root span（ROOT_CONTEXT 保证不同业务不同 traceId）；
   * 自动挂起其他 ACTIVE 业务（biz.suspend_start，span 不结束）。
   * 未签入 → warn+no-op（sign_out 后禁止起新业务）。
   * @returns bizId（未签入时 undefined）
   */
  start(bizCode: string): string | undefined {
    if (!this.deps.isSignedIn()) {
      console.warn('[nl-observe] startBusiness called while signed out; no-op');
      return undefined;
    }
    if (!BIZ_CODE_PATTERN.test(bizCode)) {
      console.warn('[nl-observe] startBusiness: bizCode must match ' + BIZ_CODE_PATTERN + '; no-op');
      return undefined;
    }
    if (this.instances.size >= MAX_CONCURRENT_INSTANCES) {
      console.warn('[nl-observe] startBusiness: concurrent business limit reached; no-op');
      return undefined;
    }
    const now = this.now();
    for (const inst of this.instances.values()) {
      if (inst.state === 'ACTIVE') {
        inst.state = 'SUSPENDED';
        inst.suspendStartMs = now;
        inst.span.addEvent(EVENT_BIZ_SUSPEND_START);
      }
    }
    this.seq += 1;
    const bizId = `b-${this.seq}`;
    const span = this.deps.tracer.startSpan(`${BIZ_BUSINESS_NAME_PREFIX}${bizCode}`, {
      attributes: {
        'nl.customer_id': this.deps.getCustomerId(),
        [ATTR_BIZ_CODE]: bizCode,
      },
    }, ROOT_CONTEXT);
    this.instances.set(bizId, {
      bizId,
      bizCode,
      state: 'ACTIVE',
      span,
      startMs: now,
      suspendedMs: 0,
    });
    return bizId;
  }

  /** 挂起业务（biz.suspend_start，span 不结束）。key 缺省 = 当前最近一个 ACTIVE 业务。 */
  suspend(key?: string): void {
    if (!key) {
      let active: BizInstance | undefined;
      for (const inst of this.instances.values()) {
        if (inst.state === 'ACTIVE') active = inst;
      }
      if (!active) {
        console.warn('[nl-observe] suspendBusiness: no ACTIVE business; no-op');
        return;
      }
      key = active.bizId;
    }
    const inst = this.findByKey(key);
    if (!inst) {
      console.warn(`[nl-observe] suspendBusiness: unknown business "${key}"; no-op`);
      return;
    }
    if (inst.state === 'SUSPENDED') {
      console.warn(`[nl-observe] suspendBusiness: business "${key}" already suspended; no-op`);
      return;
    }
    if (inst.state === 'ENDED') {
      console.warn(`[nl-observe] suspendBusiness: business "${key}" already ended; no-op`);
      return;
    }
    inst.state = 'SUSPENDED';
    inst.suspendStartMs = this.now();
    inst.span.addEvent(EVENT_BIZ_SUSPEND_START);
  }

  /** 恢复业务：累计挂起时长并记 biz.resume event（span 不结束）。
   *  维持"至多一个 ACTIVE"不变量（评审 Major-2：否则 resume 后双 ACTIVE，
   *  T14 "parent=当前 ACTIVE 业务" 取父规则破洞）：恢复前先把其他 ACTIVE 挂起。 */
  resume(key: string): void {
    const inst = this.findByKey(key);
    if (!inst || inst.state !== 'SUSPENDED') {
      console.warn(`[nl-observe] resumeBusiness: business "${key}" not suspended; no-op`);
      return;
    }
    const now = this.now();
    for (const other of this.instances.values()) {
      if (other !== inst && other.state === 'ACTIVE') {
        other.state = 'SUSPENDED';
        other.suspendStartMs = now;
        other.span.addEvent(EVENT_BIZ_SUSPEND_START);
      }
    }
    inst.suspendedMs += now - (inst.suspendStartMs ?? now);
    inst.suspendStartMs = undefined;
    inst.state = 'ACTIVE';
    inst.span.addEvent(EVENT_BIZ_RESUME);
  }

  /**
   * 结束业务（成败唯一依据：options.status；endReason 缺省 explicit）。
   * 写 biz.total_suspended_ms / biz.effective_duration_ms / biz.status / biz.end_reason，
   * end root span，发 nl.business_duration data point。
   */
  end(key: string, options: { status?: BizStatus; endReason?: BizEndReason } = {}): void {
    const inst = this.findByKey(key);
    if (!inst) {
      console.warn(`[nl-observe] endBusiness: unknown business "${key}"; no-op`);
      return;
    }
    if (inst.state === 'ENDED') {
      console.warn(`[nl-observe] endBusiness: business "${key}" already ended; no-op`);
      return;
    }
    const now = this.now();
    let suspended = inst.suspendedMs;
    if (inst.state === 'SUSPENDED' && inst.suspendStartMs != null) {
      suspended += now - inst.suspendStartMs;
    }
    const status: BizStatus = options.status ?? 'fail';
    const endReason: BizEndReason = options.endReason ?? 'explicit';
    // 时钟回退/NTP 校时可致负值：落 0 并 warn（评审 Minor：负值不得写入属性与指标）
    const rawMs = now - inst.startMs - suspended;
    const effectiveMs = rawMs < 0 ? 0 : rawMs;
    if (rawMs < 0) {
      console.warn(`[nl-observe] endBusiness: negative effective duration for "${key}"; clamped to 0`);
    }
    inst.span.setAttribute(ATTR_BIZ_TOTAL_SUSPENDED_MS, suspended);
    inst.span.setAttribute(ATTR_BIZ_EFFECTIVE_DURATION_MS, effectiveMs);
    inst.span.setAttribute(ATTR_BIZ_STATUS, status);
    inst.span.setAttribute(ATTR_BIZ_END_REASON, endReason);
    inst.span.end();
    inst.state = 'ENDED';
    this.deps.emitBusinessDuration?.(effectiveMs, {
      [ATTR_BIZ_CODE]: inst.bizCode,
      [ATTR_BIZ_STATUS]: status,
      [ATTR_BIZ_END_REASON]: endReason,
      [ATTR_BIZ_TRACE_ID]: inst.span.spanContext().traceId,
    });
    // ENDED 实例移出表（评审 Minor/安全 F1：长会话 SPA 防 Map 与 Span 引用慢性泄漏；
    // 二次 end 落入上方 unknown business warn 分支，语义不变）
    this.instances.delete(inst.bizId);
  }
}

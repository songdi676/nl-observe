/**
 * T16：vue2/vue3 指令共享生命周期（v-biz 业务 root span 起/止、v-span 子 span 起/止）。
 * 两适配层（adapters/vue2、adapters/vue3）复用本模块，保证 Vue.directive 与 app.directive
 * 行为一致（T16 AC）；不 import 'vue'，保持框架无关（optional peerDep 口径同适配层）。
 *
 * 语义（详设 §1.5 / T13 契约）：
 * - bizMounted → client.startBusiness(bizCode)（未签入/非法码由 core warn+no-op，返回 undefined）；
 * - bizUnmounted → 仅当业务仍 ACTIVE/SUSPENDED（未显式 end）时兜底
 *   endBusiness(bizId, {status:'fail', endReason:'unmount'})；已显式 end 不重复结束、不覆盖口径；
 * - spanMounted → 起子 span（parent=当前 ACTIVE 业务 root span，经 trace.setSpan(ROOT_CONTEXT, span)
 *   显式挂父，不依赖全局 context manager）；无 ACTIVE 业务 → warn+skip（与 T14 REST「无 ACTIVE 不上报」同口径）；
 * - spanUnmounted → end 已起的子 span（skip 过的 no-op）。
 *
 * 时序关键（T16 实测）：vue2 的 inserted 队列与 vue3 的 mounted post-render effects 均先子后父——
 * 同一次挂载里 v-span 元素钩子先于其 v-biz 根元素钩子触发，此刻业务尚未 startBusiness。
 * 故 v-span 的起 span 让出到微任务（queueMicrotask）：挂载 patch 为同步过程，微任务落地时
 * 同树 v-biz 已起业务，parent 取得到；若元素在微任务前已卸载（unmount 钩子先于微任务执行，
 * 经 cancelled 集合标记）则跳过，不留僵尸 span（不用 isConnected：测试容器常游离于 document，
 * 不可作为在树判据）。
 *
 * 元素态用 WeakMap/WeakSet（按注册实例隔离）：不污染元素 expando，元素回收后记录随键 GC。
 */
import { ROOT_CONTEXT, trace, type Span } from '@opentelemetry/api';
import type { NlObserveClient } from '../core/client';
import { BIZ_CODE_PATTERN } from '../core/BusinessManager';

/** v-span 子 span 名称前缀（nl.* 白名单前缀，backend AttributeKeys 注释；名称本身限低基数标签） */
export const VSPAN_NAME_PREFIX = 'nl.vspan.';

export interface DirectiveLifecycle {
  bizMounted(el: Element, bizCode: string): void;
  bizUnmounted(el: Element): void;
  spanMounted(el: Element, spanName: string): void;
  spanUnmounted(el: Element): void;
}

/** 为一个 client 创建指令生命周期（每次 registerVue*Directives 调用一份，WeakMap 互不串扰）。 */
export function createDirectiveLifecycle(client: NlObserveClient): DirectiveLifecycle {
  const bizIdByEl = new WeakMap<Element, string>();
  const spanByEl = new WeakMap<Element, Span>();
  // 已卸载标记：unmount 钩子先于 v-span 的起 span 微任务执行时置位，微任务见位即跳过（防僵尸 span）
  const spanCancelled = new WeakSet<Element>();

  const bizMounted = (el: Element, bizCode: string): void => {
    const bizId = client.startBusiness(bizCode);
    if (bizId !== undefined) {
      bizIdByEl.set(el, bizId);
    }
  };

  const bizUnmounted = (el: Element): void => {
    const bizId = bizIdByEl.get(el);
    if (bizId === undefined) return;
    bizIdByEl.delete(el);
    const state = client.businessState(bizId);
    // 未显式结束（ACTIVE/SUSPENDED）才兜底 end：end_reason=unmount、status=fail（T13 缺省 fail 口径）；
    // ENDED/未知（已显式 end，实例已移出表）不重复结束
    if (state === 'ACTIVE' || state === 'SUSPENDED') {
      client.endBusiness(bizId, { status: 'fail', endReason: 'unmount' });
    }
  };

  const startChildSpan = (el: Element, spanName: string): void => {
    if (spanCancelled.has(el)) return; // 微任务落地前元素已卸载：不起僵尸 span
    const tracer = client.getTracer();
    const active = client.activeBusiness();
    if (!tracer || !active) {
      console.warn('[nl-observe] v-span: no ACTIVE business (or tracer); child span skipped');
      return;
    }
    const span = tracer.startSpan(
      `${VSPAN_NAME_PREFIX}${spanName}`,
      { attributes: { 'nl.customer_id': client.resolvedOptions.customerId } },
      trace.setSpan(ROOT_CONTEXT, active.span),
    );
    spanByEl.set(el, span);
  };

  const spanMounted = (el: Element, spanName: string): void => {
    // 名称与 bizCode 同约束（安全评审 F2 口径：防高基数/超长串放大 OTLP 载荷）
    if (!BIZ_CODE_PATTERN.test(spanName)) {
      console.warn(`[nl-observe] v-span: name must match ${BIZ_CODE_PATTERN}; skipped`);
      return;
    }
    queueMicrotask(() => startChildSpan(el, spanName));
  };

  const spanUnmounted = (el: Element): void => {
    spanCancelled.add(el); // 起 span 微任务尚未落地即卸载：取消，不起僵尸 span
    const span = spanByEl.get(el);
    if (span === undefined) return;
    spanByEl.delete(el);
    span.end();
  };

  return { bizMounted, bizUnmounted, spanMounted, spanUnmounted };
}

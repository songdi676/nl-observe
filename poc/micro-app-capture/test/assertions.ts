/**
 * T21 POC 捕获率/兜底断言共享口径（capture-0811 / capture-rc32 复用）。
 * 口径与 T21 验收对齐：
 * #1 jsSandbox 开启：10 次 fetch ≥9 次自动产生 REST span（CAPTURE_FLOOR）；
 * #2 兜底：trackRest 补记恒 = fetchCount 条，且每条 parent=业务 root（spanId+traceId 钉住）、
 *    结构同 T14（'HTTP GET' / CLIENT / 白名单 4 属性 / route 去 query）。
 */
import { expect } from 'vitest';
import {
  ATTR_HTTP_REQUEST_METHOD,
  ATTR_HTTP_RESPONSE_STATUS_CODE,
  ATTR_HTTP_ROUTE,
  ATTR_NL_DURATION_MS,
  REST_SPAN_NAME_PREFIX,
} from '../../../sdk-web/src/core/rest/autoprofile';
import type { ScenarioResult } from '../src/harness';
import type { SpanDump } from '../src/sub-app';

export const FETCH_COUNT = 10;
export const CAPTURE_FLOOR = 9;

export const SPAN_KIND_CLIENT = 2; // @opentelemetry/api SpanKind.CLIENT

/** REST span 属性白名单 4 项（与 T14/后端 AttributeKeys.REST_SPAN_WHITELIST 一致）。 */
const REST_ATTR_KEYS_SORTED = [
  ATTR_HTTP_REQUEST_METHOD,
  ATTR_HTTP_RESPONSE_STATUS_CODE,
  ATTR_HTTP_ROUTE,
  ATTR_NL_DURATION_MS,
].sort();

/** 结果日志（POC 结论记录点：poc-notes.md T21 节数据源）。 */
export function logScenario(r: ScenarioResult): void {
  console.log(
    `[T21-POC] line=${r.line} sandbox=${r.sandbox ? 'on' : 'off'} sdk=${r.sdk} ` +
      `auto=${r.autoCount}/${r.fetchCount} fallback=${r.fallbackCount}/${r.fetchCount} ` +
      `fetchErrors=${r.fetchErrors}`,
  );
}

/** 兜底路径完整覆盖（验收 #2）：条数=fetchCount，parent/结构全对。 */
export function assertFallbackComplete(r: ScenarioResult): void {
  expect(r.fetchErrors, 'fetch 全部成功（排除网络因素干扰捕获率）').toBe(0);
  expect(r.rootSpanId, '业务 root spanId 应可取到（parent 断言基准）').toBeTruthy();
  expect(r.rootTraceId).toBeTruthy();
  expect(r.fallbackCount, '兜底 trackRest 应逐条补记（与自动捕获结果无关，恒发）').toBe(r.fetchCount);
  for (const s of r.fallbackSpans) {
    assertRestSpanStructure(s);
    expect(s.parentSpanId, '兜底 span parent 正确=业务 root spanId').toBe(r.rootSpanId);
    expect(s.traceId).toBe(r.rootTraceId);
  }
}

/** 自动埋点捕获的结构断言（捕获率达标时 span 结构同样要对）。 */
export function assertAutoStructure(r: ScenarioResult): void {
  for (const s of r.autoSpans) {
    assertRestSpanStructure(s);
    if (r.rootSpanId) {
      expect(s.parentSpanId, '自动 span parent=业务 root spanId').toBe(r.rootSpanId);
      expect(s.traceId).toBe(r.rootTraceId);
    }
  }
}

function assertRestSpanStructure(s: SpanDump): void {
  expect(s.name).toBe(`${REST_SPAN_NAME_PREFIX}GET`);
  expect(s.kind).toBe(SPAN_KIND_CLIENT);
  expect(Object.keys(s.attributes).sort(), '属性恰为白名单 4 项').toEqual(REST_ATTR_KEYS_SORTED);
  expect(s.attributes[ATTR_HTTP_REQUEST_METHOD]).toBe('GET');
  expect(s.attributes[ATTR_HTTP_ROUTE]).toBe('/api/ping'); // query 不入属性
  expect(typeof s.attributes[ATTR_HTTP_RESPONSE_STATUS_CODE]).toBe('number');
  expect(typeof s.attributes[ATTR_NL_DURATION_MS]).toBe('number');
}

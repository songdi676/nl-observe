/**
 * T21 POC 捕获率场景：@micro-zoe/micro-app 0.8.11（npm dist-tag latest=1.0.0-rc.32，
 * 稳定线 0.8.11——集成文档推荐线）。
 *
 * 0.8.11 沙箱语义（源码实测）：声明式 <micro-app> 元素，jsSandbox 默认开启，
 * disable-sandbox 属性关闭；with-sandbox 缓存 window/self/globalThis/location/navigator
 * （无 fetch/document），proxy get 回落 rawWindow。
 *
 * 验收 #1：jsSandbox 开启（默认态）→ 10 次 fetch ≥9 次自动产生 REST span；
 * 验收 #2：兜底 trackRest 恒完整覆盖且 parent 正确（无论捕获率是否达标都断言——
 * 兜底是「不达标时」的完整覆盖保证，必须实测有效）。
 */
import { describe, expect, it } from 'vitest';
import { runCaptureScenario } from '../src/harness';
import {
  CAPTURE_FLOOR,
  FETCH_COUNT,
  assertAutoStructure,
  assertFallbackComplete,
  logScenario,
} from './assertions';

describe('T21 POC：micro-app 0.8.11 捕获率矩阵', () => {
  it(
    'jsSandbox 开启（默认）+ 子应用自初始化 SDK：捕获率 ≥9/10',
    async () => {
      const r = await runCaptureScenario({ line: '0.8.11', sandbox: true, sdk: 'sub', fetchCount: FETCH_COUNT });
      logScenario(r);
      expect(r.autoCount, '0.8.11 沙箱内自动埋点捕获率').toBeGreaterThanOrEqual(CAPTURE_FLOOR);
      assertAutoStructure(r);
      assertFallbackComplete(r);
    },
    60_000,
  );

  it(
    'jsSandbox 开启（默认）+ 宿主初始化 SDK：捕获率 ≥9/10',
    async () => {
      const r = await runCaptureScenario({ line: '0.8.11', sandbox: true, sdk: 'host', fetchCount: FETCH_COUNT });
      logScenario(r);
      expect(r.autoCount, '0.8.11 沙箱内宿主 SDK 包装捕获率').toBeGreaterThanOrEqual(CAPTURE_FLOOR);
      assertAutoStructure(r);
      assertFallbackComplete(r);
    },
    60_000,
  );

  it(
    'jsSandbox 关闭（disable-sandbox）+ 子应用自初始化 SDK：10/10 基线',
    async () => {
      const r = await runCaptureScenario({ line: '0.8.11', sandbox: false, sdk: 'sub', fetchCount: FETCH_COUNT });
      logScenario(r);
      expect(r.autoCount).toBe(FETCH_COUNT);
      assertAutoStructure(r);
      assertFallbackComplete(r);
    },
    60_000,
  );

  it(
    'jsSandbox 关闭（disable-sandbox）+ 宿主初始化 SDK：10/10 基线',
    async () => {
      const r = await runCaptureScenario({ line: '0.8.11', sandbox: false, sdk: 'host', fetchCount: FETCH_COUNT });
      logScenario(r);
      expect(r.autoCount).toBe(FETCH_COUNT);
      assertAutoStructure(r);
      assertFallbackComplete(r);
    },
    60_000,
  );
});

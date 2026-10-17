/**
 * T23 指标 E2E（详设 §6.4）：first_screen_load data point 存在性（不断言精确值）。
 * 宿主页真实浏览器加载 → SDK 记 nl.first_screen_load（LCP 优先、load 兜底，每页一个）
 * → OTLP/HTTP metrics → 后端 → 查询 API /metrics/summary 的 firstScreenLoadP95 非空。
 *
 * env-gated：E2E_BACKEND_URL 未设置 → skip（内网 Kafka/Databend 可达性未知，待澄清 #7）。
 */
import { test, expect } from '@playwright/test';
import {
  dayOffset,
  e2eEnabled,
  getMetricsSummary,
  hostUrl,
  newCustomerId,
  otlpTracesUrl,
  pollUntil,
} from './support/e2e-env';

test.describe('metrics：first_screen_load data point 存在性', () => {
  test('宿主页加载后查询 API 可见首屏指标（存在性，不断言精确值）', async ({ page }) => {
    test.skip(!e2eEnabled(), 'E2E_BACKEND_URL 未设置：全链路 E2E skip（env-gated）');

    const customer = newCustomerId();
    await page.goto(hostUrl({ customer, otlp: otlpTracesUrl() }));
    await expect(page.getByTestId('host-status')).toContainText('INITIALIZED');

    // 首屏指标在 load 时记录；等一个周期 reader 窗口后 shutdown 冲刷（DELTA 导出）
    await page.waitForTimeout(3000);
    await page.evaluate(() => {
      const w = window as unknown as { __e2eFlush?: () => Promise<void> };
      return w.__e2eFlush?.();
    });

    const summary = await pollUntil(
      async () => {
        const s = await getMetricsSummary(dayOffset(-1), dayOffset(2), customer);
        return s && typeof s.firstScreenLoadP95 === 'number' ? s : null;
      },
      { label: 'first_screen_load data point 在 metrics/summary 可见' },
    );

    // 存在性口径：非空、有限、正值（精确值不断言）
    expect(Number.isFinite(summary.firstScreenLoadP95!)).toBe(true);
    expect(summary.firstScreenLoadP95!).toBeGreaterThan(0);
  });
});

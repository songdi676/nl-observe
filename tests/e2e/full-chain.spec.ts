/**
 * T23 全链路 E2E（详设 §6.4；AC-01…AC-10 端到端验收）：
 * vue3 宿主页（tests/e2e/host，vite dev）→ 签入客户 → 业务 A（真实 fetch 至 mock 后端）
 * → 切业务 B（A 自动挂起）→ 恢复 A 并结束 → 结束 B → 经查询 API 断言：
 * 2 个业务、traceId 不同、A 的 REST span 归属 A（parent=A root）、
 * effectiveDurationMs 在宽松区间（墙钟-Σ挂起，容忍计时抖动）。
 *
 * env-gated：E2E_BACKEND_URL 未设置 → skip（内网 Kafka/Databend 可达性未知，待澄清 #7）。
 * 本地离线自检：npm run test:e2e:local（stub 后端，tests/e2e/support/stub-backend.mjs）。
 */
import { test, expect } from '@playwright/test';
import {
  dayOffset,
  e2eEnabled,
  getTrace,
  hostUrl,
  listBusinesses,
  newCustomerId,
  otlpTracesUrl,
  pollUntil,
} from './support/e2e-env';

test.describe('full-chain：宿主页办理 → OTLP → 后端 → 查询 API', () => {
  test('签入→A(fetch)→B(A 挂起)→恢复并结束 A→结束 B→查询断言', async ({ page }) => {
    test.skip(!e2eEnabled(), 'E2E_BACKEND_URL 未设置：全链路 E2E skip（env-gated）');

    const customer = newCustomerId();
    await page.goto(hostUrl({ customer, otlp: otlpTracesUrl() }));
    await expect(page.getByTestId('host-status')).toContainText('INITIALIZED');

    await page.getByTestId('btn-signin').click();
    await expect(page.getByTestId('host-status')).toContainText('SIGNED_IN');

    await page.getByTestId('btn-start-a').click();
    await page.getByTestId('btn-fetch').click();
    await expect(page.getByTestId('host-status')).toContainText('FETCHED');

    // 切业务 B：startBusiness 自动挂起 A（页内状态先行断言，后端断言稍后）
    await page.waitForTimeout(1200);
    await page.getByTestId('btn-start-b').click();
    await expect(page.getByTestId('host-status')).toContainText('A=SUSPENDED');

    // 恢复 A → 结束 A → 结束 B
    await page.waitForTimeout(1500);
    await page.getByTestId('btn-resume-a').click();
    await page.waitForTimeout(1200);
    await page.getByTestId('btn-end-a').click();
    await page.getByTestId('btn-end-b').click();

    // flush：shutdown 冲刷 BSP + metric reader（sendBeacon OTLP/HTTP 尽力上报）
    await page.evaluate(() => {
      const w = window as unknown as { __e2eFlush?: () => Promise<void> };
      return w.__e2eFlush?.();
    });

    // —— 查询 API 断言（存储管道有秒级时延，轮询至数据到达）——
    const items = await pollUntil(
      async () => {
        const list = await listBusinesses(customer, dayOffset(-1), dayOffset(2));
        return list.length >= 2 ? list : null;
      },
      { label: '查询 API 返回 2 个业务' },
    );

    const a = items.find((i) => i.bizCode === 'ORDER');
    const b = items.find((i) => i.bizCode === 'PAY');
    expect(a, '业务 A（ORDER）存在').toBeDefined();
    expect(b, '业务 B（PAY）存在').toBeDefined();

    // traceId 不同（独立 ROOT_CONTEXT 父）
    expect(a!.traceId).toMatch(/^[0-9a-f]{32}$/);
    expect(b!.traceId).toMatch(/^[0-9a-f]{32}$/);
    expect(a!.traceId).not.toBe(b!.traceId);

    // 显式结束口径
    expect(a!.status).toBe('success');
    expect(a!.endReason).toBe('explicit');
    expect(b!.status).toBe('success');
    expect(b!.endReason).toBe('explicit');

    // effectiveDurationMs 宽松区间：页内时序 A 墙钟≈5.1s-挂起≈1.5s≈3.6s，B≈2.7s；
    // 容忍浏览器事件循环/网络抖动，只断言数量级（详设 §6.4「宽松」口径）
    expect(a!.effectiveDurationMs).toBeGreaterThanOrEqual(1500);
    expect(a!.effectiveDurationMs).toBeLessThanOrEqual(6000);
    expect(b!.effectiveDurationMs).toBeGreaterThanOrEqual(1000);
    expect(b!.effectiveDurationMs).toBeLessThanOrEqual(6000);

    // A 的 REST span 归属 A：trace 内 REST span 的 parent = A root span
    const aSpans = await pollUntil(() => getTrace(customer, a!.traceId), {
      label: 'A trace 可查',
    });
    const aRoot = aSpans!.find((s) => s.name === 'biz.business.ORDER');
    expect(aRoot, 'A root span 存在于 trace').toBeDefined();
    const rest = aSpans!.filter((s) => s.name.startsWith('HTTP '));
    expect(rest.length, 'A trace 含 REST span（fetch 自动埋点）').toBeGreaterThan(0);
    expect(rest[0].parentSpanId).toBe(aRoot!.spanId);
    expect(rest[0].attributes['http.request.method']).toBe('GET');
    expect(rest[0].attributes['http.response.status_code']).toBe(200);

    // A root span 记有挂起/恢复事件（切 B 挂起、恢复 A 的语义落 span）
    const aEventNames = aRoot!.events.map((e) => e.name);
    expect(aEventNames).toContain('biz.suspend_start');
    expect(aEventNames).toContain('biz.resume');

    // B trace 无 REST span：fetch 发生在 A 活跃窗口 → 归属 A 而非 B
    const bSpans = await pollUntil(() => getTrace(customer, b!.traceId), {
      label: 'B trace 可查',
    });
    expect(bSpans!.filter((s) => s.name.startsWith('HTTP '))).toHaveLength(0);
  });
});

/**
 * T21 POC：micro-app 1.0.0-rc.32 线——jsdom 保真度探针 + 记录结论。
 *
 * 实测结论（jsdom 26.1.0 + vitest 5.0.4，2026-10-17；探针实测，非推演）：
 * rc.32 的 `customElements.define('micro-app', …)` 只发生在 `start()` 内
 * （lib 唯一调用点 defineElement）——**探针必须先 start() 再 renderApp**，
 * 否则 renderApp 创建的是未定义元素，connectedCallback 永不触发，挂载路径
 * 根本不执行（探针空转）。start() 之后：
 * - with-sandbox 线以 `class CustomWindow {}`（普通对象）作 proxyWindow target，
 *   挂载收尾执行 `sandBox.microAppWindow.dispatchEvent(CustomEvent)`（statechange/
 *   mounted 状态事件，lib 源码 dispatchCustomEventToMicroApp）——jsdom 的
 *   EventTarget 品牌校验拒绝（TypeError: "not a valid instance of EventTarget"）
 *   → MOUNTED 生命周期不达基座元素 → renderApp 的 Promise 永不 resolve。
 *   真实浏览器该路径成立，属 jsdom 环境保真缺口，非 SDK 埋点问题；0.8.11 稳定线
 *   同语义路径在 jsdom 下全绿（见 capture-0811.spec.ts）。
 *
 * POC 裁决：捕获率验收以 0.8.11 稳定线（集成文档推荐线）为准；兜底路径（trackRest/v-span）
 * 是框架无关的 SDK 路径，其有效性由 0.8.11 沙箱场景 + 无沙箱对照组证明，结论对 rc.32 同样
 * 成立。rc.32 沙箱线的自动埋点捕获率在 jsdom 下不可测（品牌校验），留真实浏览器复验
 * （poc-notes T21 节跟进项）；rc.32 无沙箱线在 jsdom 下可跑通（本 spec 第二探针）。
 *
 * 本 spec 钉住探针行为本身（防结论静默漂移）：
 *  - sandbox 开：MOUNTED 不达（renderApp 预算内不 resolve）+ 品牌校验 TypeError
 *    以 unhandled rejection 形式被探针自捕获（文案断言钉住根因）；
 *  - sandbox 关：renderApp resolve(true)（MOUNTED 达）+ 子应用就绪信号
 *    （__POC_READY__）达——rc.32 无沙箱线在 jsdom 下可跑通（实测）。
 *    （旧结论「就绪不达」是探针未 start() 的空转假象，已更正。）
 *  - 两个探针用例共享进程内 start()/挂载状态，**仅整文件顺序执行有效**，
 *    不可单独运行或乱序（评审 corr Min-2）。
 *
 * unhandled rejection 处理：品牌校验崩溃的 Promise 链必然产生 micro-app 内部
 * unhandled rejection（即结论本身）。探针在 beforeAll 换装 process 级监听器
 * 自捕获并断言文案、afterAll 还原 vitest 监听器——作用域只限本文件 worker
 * （vitest 按文件隔离 worker），主验收文件与根聚合不受任何项目级 flag 影响
 * （评审 arch m-1 / sec M-1 / corr Min-1：不设 dangerouslyIgnoreUnhandledErrors）。
 * 配套实测：沙箱挂载崩溃后 unmountApp 挂起（30s 预算内不 resolve）——
 * 下方 unmount 一律 race 超时预算，不裸 await（评审 arch M-1 实证）。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import microAppRc from 'micro-app-rc';
import {
  buildSubAppBundle,
  scenarioName,
  startMockBackend,
  startRcOnce,
  waitFor,
  type MockBackend,
} from '../src/harness';

const PROBE_BUDGET_MS = 8_000;
const UNMOUNT_BUDGET_MS = 5_000;

// ---- 探针自捕获 unhandled rejection（换装 process 级监听器，afterAll 还原） ----
const rejectionReasons: string[] = [];
let savedListeners: ((...args: unknown[]) => void)[] = [];

beforeAll(() => {
  savedListeners = process.listeners('unhandledRejection') as ((...args: unknown[]) => void)[];
  process.removeAllListeners('unhandledRejection');
  process.on('unhandledRejection', (reason: unknown) => {
    rejectionReasons.push(
      reason instanceof Error ? `${reason.name}: ${reason.message}` : String(reason),
    );
  });
});

afterAll(() => {
  process.removeAllListeners('unhandledRejection');
  for (const l of savedListeners) process.on('unhandledRejection', l);
});

/** unmountApp 崩溃后可能挂起：race 预算，返回是否收敛（不阻塞、不吞错）。 */
async function raceUnmount(name: string): Promise<'converged' | 'hung'> {
  return Promise.race([
    microAppRc
      .unmountApp(name, { destroy: true })
      .then(() => 'converged' as const, () => 'converged' as const),
    new Promise<'hung'>((r) => setTimeout(() => r('hung'), UNMOUNT_BUDGET_MS)),
  ]);
}

describe('T21 POC：rc.32 线 jsdom 保真探针（结论：sandbox 线在 jsdom 下不可跑通，验收基线取 0.8.11）', () => {
  let backend: MockBackend | undefined;
  let container: HTMLElement | undefined;

  afterEach(async () => {
    container?.remove();
    container = undefined;
    await backend?.stop();
    backend = undefined;
  });

  it('jsSandbox 开启：MOUNTED 不达（品牌校验 TypeError 被探针捕获，钉住根因）', async () => {
    startRcOnce(); // rc.32 define('micro-app') 仅在 start() 内——不先 start 则探针空转
    const name = scenarioName('1.0.0-rc.32', true, 'sub');
    const holder = { js: '' };
    backend = await startMockBackend(() => holder.js);
    holder.js = await buildSubAppBundle('sub', backend.origin);
    container = document.createElement('div');
    document.body.appendChild(container);

    // 注：rc.32 的 statechange/mounted 事件在 renderApp 内部创建的 <micro-app>
    // 元素上派发（CustomEvent 默认不冒泡）——基座 container 上的监听是假信号，
    // 不采用；MOUNTED 达/不达由 renderApp 的 resolve 形态钉（resolve 于
    // MOUNTED(true)/ERROR(false)，预算内不 resolve 即生命周期卡死）。
    const render = microAppRc.renderApp({
      name,
      url: backend.appUrl,
      container,
      'disable-sandbox': false,
    });
    const resolved = await Promise.race([
      render.then(() => true),
      new Promise<boolean>((r) => setTimeout(() => r(false), PROBE_BUDGET_MS)),
    ]);

    // 记录结论：jsdom 下 rc.32 沙箱挂载不完成（MOUNTED 被品牌校验阻断）
    expect(resolved, 'rc.32 renderApp 在 jsdom 下不应 resolve（MOUNTED 被品牌校验阻断）').toBe(false);
    // 钉住根因：品牌校验 TypeError 必须出现在自捕获的 unhandled rejection 中
    // （rejection 在 8s 预算窗口内的微任务链中产生，此处直接断言）
    expect(
      rejectionReasons.some((m) => m.includes('not a valid instance of EventTarget')),
      `应捕获品牌校验 TypeError；实际捕获：${JSON.stringify(rejectionReasons)}`,
    ).toBe(true);

    const unmount = await raceUnmount(name);
    console.log(`[T21-POC] rc32 sandbox=on unmount after crash: ${unmount}`);
  }, 30_000);

  it('jsSandbox 关闭（disable-sandbox）：MOUNTED 达 + 子应用就绪信号达（无沙箱线 jsdom 可跑通）', async () => {
    startRcOnce(); // 幂等：整文件共享一次 start()（rc.32 重复 start 会 logError）
    const name = scenarioName('1.0.0-rc.32', false, 'sub');
    const holder = { js: '' };
    backend = await startMockBackend(() => holder.js);
    holder.js = await buildSubAppBundle('sub', backend.origin);
    container = document.createElement('div');
    document.body.appendChild(container);

    const render = microAppRc.renderApp({
      name,
      url: backend.appUrl,
      container,
      'disable-sandbox': true,
    });
    let resolution = 'timeout（预算内未 resolve/reject）';
    const tracked = render.then(
      (ok) => {
        resolution = `resolved(${ok})`;
      },
      (err: unknown) => {
        resolution = `rejected(${String(err)})`;
      },
    );
    await Promise.race([tracked, new Promise((r) => setTimeout(r, PROBE_BUDGET_MS))]);
    console.log(`[T21-POC] rc32 sandbox=off lifecycle: ${resolution}`);
    // 实测：无沙箱线不触发 CustomWindow.dispatchEvent → 品牌校验不参与，
    // MOUNTED 正常达，renderApp resolve(true)（即便前序沙箱探针崩溃后进程内
    // 状态残留，亦不影响本线——旧「完成性依赖先前状态」说法系未 start 假象）
    expect(resolution, 'rc.32 无沙箱线 renderApp 应 resolve(true)（MOUNTED 达）').toBe('resolved(true)');

    const readyEl = await waitFor(
      () => {
        const el = container!.querySelector('#sub-app') as (HTMLElement & { __POC_READY__?: boolean }) | null;
        return el && el.__POC_READY__ ? el : false;
      },
      5_000,
    );
    // 记录结论：rc.32 无沙箱线下子应用 bundle 在 jsdom 内执行、就绪信号达
    // （捕获率可测性由 0.8.11 稳定线承担；本探针只钉「无沙箱线可跑通」事实）
    expect(readyEl, 'rc.32 无沙箱线子应用就绪信号应达（探针钉住该事实）').toBeTruthy();

    const unmount = await raceUnmount(name);
    console.log(`[T21-POC] rc32 sandbox=off unmount: ${unmount}`);
  }, 30_000);
});

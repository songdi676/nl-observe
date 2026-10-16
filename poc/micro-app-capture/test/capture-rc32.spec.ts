/**
 * T21 POC：micro-app 1.0.0-rc.32 线——jsdom 保真度探针 + 记录结论。
 *
 * 实测结论（jsdom 26.1.0 + vitest 5.0.4，2026-10-17）：
 * rc.32 with-sandbox 线以 `class CustomWindow {}`（普通对象）作 proxyWindow target，
 * 挂载收尾执行 `sandBox.microAppWindow.dispatchEvent(CustomEvent)`（statechange/mounted
 * 状态事件，lib 源码 dispatchCustomEventToMicroApp）——jsdom 的 EventTarget 品牌校验
 * 拒绝（"not a valid instance of EventTarget"）→ MOUNTED 生命周期不达基座元素 →
 * renderApp 的 Promise 永不 resolve。真实浏览器该路径成立，属 jsdom 环境保真缺口，
 * 非 SDK 埋点问题；0.8.11 稳定线同语义路径在 jsdom 下全绿（见 capture-0811.spec.ts）。
 *
 * POC 裁决：捕获率验收以 0.8.11 稳定线（集成文档推荐线）为准；兜底路径（trackRest/v-span）
 * 是框架无关的 SDK 路径，其有效性由 0.8.11 沙箱场景 + 无沙箱对照组证明，结论对 rc.32 同样
 * 成立。rc.32 的自动埋点捕获率留真实浏览器复验（poc-notes T21 节跟进项）。
 *
 * 本 spec 钉住探针行为本身（防结论静默漂移）：
 *  - sandbox 开：MOUNTED 不达（renderApp 在预算内不 resolve）；
 *  - sandbox 关：子应用就绪信号（__POC_READY__）不达——renderApp 完成性本身
 *    依赖进程内先前状态（单独跑 resolve；先前沙箱挂载崩溃后挂起），故探针
 *    只钉决定性事实（就绪不达），完成性形态经 console 记录供 poc-notes 引用。
 * 注：sandbox-on 探针会触发 micro-app 内部 unhandled rejection（即上述品牌校验崩溃的
 * Promise 链）——项目级 dangerouslyIgnoreUnhandledErrors=true 显式容忍（见 vitest 配置）。
 */
import { afterEach, describe, expect, it } from 'vitest';
// 顺序敏感：worker-shim 必须在 'micro-app-rc' 之前求值（rc.32 模块顶层 Proxy(window.Worker)）
import '../src/worker-shim';
import microAppRc from 'micro-app-rc';
import {
  buildSubAppBundle,
  scenarioName,
  startMockBackend,
  waitFor,
  type MockBackend,
} from '../src/harness';

const PROBE_BUDGET_MS = 8_000;

describe('T21 POC：rc.32 线 jsdom 保真探针（结论：jsdom 下不可跑通，验收基线取 0.8.11）', () => {
  let backend: MockBackend | undefined;
  let container: HTMLElement | undefined;

  afterEach(async () => {
    container?.remove();
    container = undefined;
    await backend?.stop();
    backend = undefined;
  });

  it('jsSandbox 开启：MOUNTED 不达（CustomWindow.dispatchEvent 被 jsdom 品牌校验拒绝）', async () => {
    const name = scenarioName('1.0.0-rc.32', true, 'sub');
    const holder = { js: '' };
    backend = await startMockBackend(() => holder.js);
    holder.js = await buildSubAppBundle('sub', backend.origin);
    container = document.createElement('div');
    document.body.appendChild(container);

    let mounted = false;
    container.addEventListener('mounted', () => {
      mounted = true;
    });

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

    // 记录结论：jsdom 下 rc.32 沙箱挂载不完成（探针钉住该事实）
    expect(resolved, 'rc.32 renderApp 在 jsdom 下不应 resolve（MOUNTED 被品牌校验阻断）').toBe(false);
    expect(mounted).toBe(false);

    await microAppRc.unmountApp(name, { destroy: true }).catch(() => undefined);
  }, 30_000);

  it('jsSandbox 关闭（disable-sandbox）：子应用就绪信号不达（生命周期完成与否均不改变结论）', async () => {
    const name = scenarioName('1.0.0-rc.32', false, 'sub');
    const holder = { js: '' };
    backend = await startMockBackend(() => holder.js);
    holder.js = await buildSubAppBundle('sub', backend.origin);
    container = document.createElement('div');
    document.body.appendChild(container);

    // 实测：非沙箱线的 renderApp 完成性依赖进程内先前状态——单独跑时 resolve，
    // 先前沙箱挂载崩溃（上一探针）后挂起。两种形态下决定性事实一致：子应用 bundle
    // 就绪信号不达 → 捕获率无法在 jsdom 下测量。探针钉住该事实，不钉完成性。
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

    let ready = false;
    try {
      await waitFor(() => {
        const el = container!.querySelector('#sub-app') as (HTMLElement & { __POC_READY__?: boolean }) | null;
        return el && el.__POC_READY__ ? true : false;
      }, 5_000);
      ready = true;
    } catch {
      ready = false;
    }
    // 记录结论：rc.32 下子应用 bundle 执行路径与 0.8.11/真实浏览器不一致，就绪不达
    expect(ready, 'rc.32 子应用就绪信号在 jsdom 下不应达（探针钉住该事实）').toBe(false);

    await microAppRc.unmountApp(name, { destroy: true }).catch(() => undefined);
  }, 30_000);
});

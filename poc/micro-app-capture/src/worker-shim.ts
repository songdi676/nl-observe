/**
 * jsdom 环境弥合（经本工程 vitest.config.ts 的 setupFiles 集中保证先于一切测试模块
 * 求值——不再依赖各 spec 手工维护「先于 micro-app-rc」的导入顺序，评审 m-4）：
 * micro-app 1.0.0-rc.32 在模块顶层执行 `new Proxy(window.Worker, ...)`
 * （原 src/proxies/worker.ts:78，源码映射定位），jsdom 无 window.Worker →
 * 导入即抛 "Cannot create proxy with a non-object as target or handler"。
 * 0.8.11 无 Worker 代理（lib 实测无 Worker 引用），不受影响。
 * 桩为 no-op（POC 子应用不用 Worker；rc.32 仅代理其构造器，不调用）。
 */
/* eslint-disable @typescript-eslint/no-unused-vars -- 桩签名必须与 Worker/EventTarget 面一致，参数天然不用 */
class WorkerStub {
  onerror: unknown = null;
  onmessage: unknown = null;
  onmessageerror: unknown = null;
  constructor(_url?: string) {
    /* no-op */
  }
  addEventListener(_type: string, _listener: unknown, _options?: unknown): void {
    /* no-op */
  }
  removeEventListener(_type: string, _listener: unknown, _options?: unknown): void {
    /* no-op */
  }
  dispatchEvent(_event: unknown): boolean {
    return true;
  }
  postMessage(..._args: unknown[]): void {
    /* no-op */
  }
  terminate(): void {
    /* no-op */
  }
}

const win = (globalThis as unknown as { window?: Record<string, unknown> }).window;
if (win && !win.Worker) {
  win.Worker = WorkerStub;
}
if (!(globalThis as Record<string, unknown>).Worker) {
  (globalThis as Record<string, unknown>).Worker = WorkerStub;
}

export {};

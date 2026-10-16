/**
 * T21 POC 测试侧工装：mock 后端 + 子应用 bundle 构建 + micro-app 挂载（双版本线）+ 场景编排。
 *
 * 环境保真 shim（jsdom 与浏览器的差异，POC 必须显式弥合）：
 * - 浏览器里 globalThis === window，SDK 包装 globalThis.fetch 即包装 window.fetch；
 *   vitest jsdom 环境下 globalThis 是 Node 全局（fetch=undici），window 是 jsdom window
 *   （无 fetch）。micro-app 的 rawWindow.fetch 与子应用 fetch 走 window 侧，SDK 包装走
 *   globalThis 侧——不弥合则「宿主 SDK」场景必然 0 捕获（环境假象而非沙箱结论）。
 *   installFetchBindingShim() 把 window.fetch 与 globalThis.fetch 定义成同一绑定的
 *   get/set（任一侧写入另一侧可见），还原浏览器语义。
 * - micro-app 0.8.11 自带 requestIdleCallback polyfill；jsdom 有 customElements/
 *   MutationObserver/DOMParser，双版本线均可在 jsdom 内运行（实测见 poc-notes T21 节）。
 */
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { InMemorySpanExporter, SimpleSpanProcessor, type ReadableSpan } from '@opentelemetry/sdk-trace-base';
import { NlObserveClient } from '@nl-observe/sdk-web';
// 0.8.11：unmountApp 是独立命名导出（不在 MicroApp 实例上——与 rc.32 的 this.unmountApp 差异，
// POC 记录点：双版本线 API 面不一致）
import microApp0811, { unmountApp as unmountApp0811 } from '@micro-zoe/micro-app';
// 顺序敏感：worker-shim 必须在 'micro-app-rc' 之前求值（rc.32 模块顶层 Proxy(window.Worker)，
// jsdom 无 Worker → 导入即抛）
import './worker-shim';
import microAppRc from 'micro-app-rc';
import {
  filterRestSpans,
  type SpanDump,
  type SubAppPhaseDetail,
  type SubAppTrackRestDetail,
} from './sub-app';

// 工程目录：不依赖 import.meta.url（vitest jsdom 环境会改写其 scheme，fileURLToPath 抛
// "URL must be of scheme file"）——按 cwd 探测：根聚合跑 → <cwd>/poc/micro-app-capture；
// 独立跑（npm test -w poc-micro-app-capture）→ cwd 即工程目录。
const probe = join(process.cwd(), 'poc', 'micro-app-capture');
const projectDir = existsSync(probe) ? probe : process.cwd();

// ---- fetch 绑定 shim（幂等） ----
let fetchShimInstalled = false;

export function installFetchBindingShim(): void {
  if (fetchShimInstalled) return;
  const w = globalThis as unknown as { window?: Record<string, unknown> };
  if (!w.window) throw new Error('jsdom window missing: run with environment jsdom');
  let current: unknown = globalThis.fetch ?? w.window.fetch;
  const descriptor: PropertyDescriptor = {
    configurable: true,
    enumerable: true,
    get: () => current,
    set: (v: unknown) => {
      current = v;
    },
  };
  Object.defineProperty(globalThis, 'fetch', descriptor);
  Object.defineProperty(w.window, 'fetch', descriptor);
  fetchShimInstalled = true;
}

// ---- mock 后端（/api/ping 200；/sub-app/index.html 子应用 HTML；/sub-app.js bundle） ----
export interface MockBackend {
  origin: string;
  appUrl: string;
  stop(): Promise<void>;
}

export async function startMockBackend(bundle: () => string): Promise<MockBackend> {
  const html =
    '<!DOCTYPE html><html><head><title>poc-sub</title></head><body>' +
    '<div id="sub-app"></div>' +
    '<script src="/sub-app.js"></script>' +
    '</body></html>';
  const server: Server = createServer((req, res) => {
    const u = new URL(req.url ?? '/', 'http://127.0.0.1');
    if (req.method === 'GET' && u.pathname === '/api/ping') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"ok":true}');
      return;
    }
    if (req.method === 'GET' && u.pathname === '/sub-app.js') {
      res.writeHead(200, { 'content-type': 'text/javascript' });
      res.end(bundle());
      return;
    }
    if (req.method === 'GET' && u.pathname === '/sub-app/index.html') {
      res.writeHead(200, { 'content-type': 'text/html' });
      res.end(html);
      return;
    }
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found');
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = (server.address() as AddressInfo).port;
  const origin = `http://127.0.0.1:${port}`;
  return {
    origin,
    appUrl: `${origin}/sub-app/index.html`,
    stop: async () => {
      // undici keep-alive 会挂住 close()
      (server as Server & { closeAllConnections?: () => void }).closeAllConnections?.();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}

// ---- 子应用 bundle（esbuild IIFE，SDK 源码经 alias 打进 bundle） ----
// 构建在子进程跑：esbuild JS API 模块加载期断言 TextEncoder/Uint8Array 同源性，
// jsdom worker 内必假（环境假象）——见 app/build-bundle.mjs 头注。
export async function buildSubAppBundle(mode: 'sub' | 'host', backendOrigin: string): Promise<string> {
  const script = join(projectDir, 'app', 'build-bundle.mjs');
  return new Promise((resolve, reject) => {
    execFile(
      process.execPath,
      [script, mode, backendOrigin],
      { cwd: projectDir, maxBuffer: 32 * 1024 * 1024 },
      (err, stdout, stderr) => {
        if (err) reject(new Error(`buildSubAppBundle failed: ${stderr || err.message}`));
        else resolve(stdout);
      },
    );
  });
}

// ---- 等待工具 ----
export async function waitFor<T>(
  probe: () => T | undefined | false,
  timeoutMs = 10_000,
  intervalMs = 10,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const v = probe();
    if (v !== undefined && v !== false) return v;
    if (Date.now() > deadline) throw new Error('waitFor: timeout');
    await new Promise((r) => setTimeout(r, intervalMs));
  }
}

export function waitForEvent(target: EventTarget, name: string, timeoutMs = 10_000): Promise<CustomEvent> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      target.removeEventListener(name, onEvent);
      reject(new Error(`waitForEvent: timeout waiting for '${name}'`));
    }, timeoutMs);
    const onEvent = (ev: Event): void => {
      clearTimeout(timer);
      target.removeEventListener(name, onEvent);
      resolve(ev as CustomEvent);
    };
    target.addEventListener(name, onEvent);
  });
}

// ---- micro-app 挂载（双版本线 API 差异即 POC 记录点） ----
export type VersionLine = '0.8.11' | '1.0.0-rc.32';

export function scenarioName(line: VersionLine, sandbox: boolean, sdk: 'sub' | 'host'): string {
  return `poc-${line.replace(/\W/g, '_')}-${sdk}-${sandbox ? 'sb' : 'ns'}`;
}

// start() 每版本线只调一次（rc.32 重复 start 会 logError；0.8.11 重复会 warn——均为噪音）
let started0811 = false;
let startedRc = false;

export async function mountMicroApp(
  line: VersionLine,
  opts: { name: string; url: string; container: Element; sandbox: boolean },
): Promise<void> {
  if (line === '0.8.11') {
    // 0.8.11：无 createMicroApp——声明式 <micro-app> 自定义元素；沙箱默认开启，
    // disable-sandbox 属性关闭（「jsSandbox 开启」= 默认态）；shadowDOM 默认 false（实测源码）
    if (!started0811) {
      microApp0811.start();
      started0811 = true;
    }
    const el = document.createElement('micro-app');
    el.setAttribute('name', opts.name);
    el.setAttribute('url', opts.url);
    if (!opts.sandbox) el.setAttribute('disable-sandbox', 'true');
    opts.container.appendChild(el);
    return;
  }
  // 1.0.0-rc.32：renderApp 命令式挂载（options 非 container 键 → setAttribute，
  // 'disable-sandbox': true 即关沙箱）；resolve 于 MOUNTED/ERROR 生命周期
  if (!startedRc) {
    microAppRc.start();
    startedRc = true;
  }
  const ok = await microAppRc.renderApp({
    name: opts.name,
    url: opts.url,
    container: opts.container,
    'disable-sandbox': !opts.sandbox,
  });
  if (!ok) throw new Error(`rc.32 renderApp failed for '${opts.name}' (micro-app ERROR lifecycle)`);
}

export async function unmountMicroApp(line: VersionLine, name: string): Promise<void> {
  if (line === '0.8.11') {
    await unmountApp0811(name, { destroy: true });
  } else {
    await microAppRc.unmountApp(name, { destroy: true });
  }
}

// ---- 场景编排 ----
export interface ScenarioOptions {
  line: VersionLine;
  sandbox: boolean;
  sdk: 'sub' | 'host';
  fetchCount?: number;
}

export interface ScenarioResult {
  line: VersionLine;
  sandbox: boolean;
  sdk: 'sub' | 'host';
  fetchCount: number;
  /** 自动埋点捕获的 REST span 数（验收 #1 分子） */
  autoCount: number;
  autoSpans: SpanDump[];
  /** trackRest 兜底补记的 REST span 数（验收 #2：应恒 = fetchCount） */
  fallbackCount: number;
  fallbackSpans: SpanDump[];
  /** 业务 root span（parent 断言基准；经 activeBusiness() 取活 span） */
  rootSpanId?: string;
  rootTraceId?: string;
  fetchErrors: number;
}

function toDump(span: ReadableSpan): SpanDump {
  return {
    name: span.name,
    traceId: span.spanContext().traceId,
    spanId: span.spanContext().spanId,
    parentSpanId: span.parentSpanContext?.spanId,
    kind: span.kind,
    attributes: { ...span.attributes },
  };
}

function restDumps(exporter: InMemorySpanExporter): SpanDump[] {
  return filterRestSpans(exporter.getFinishedSpans().map(toDump));
}

/**
 * 单场景：挂载子应用 → 阶段1（自动捕获）→ 阶段2（兜底覆盖）→ 卸载。
 * sub 模式 span 从沙箱内导出器经事件 detail 取；host 模式从宿主导出器直接读。
 */
export async function runCaptureScenario(opts: ScenarioOptions): Promise<ScenarioResult> {
  installFetchBindingShim();
  const fetchCount = opts.fetchCount ?? 10;
  const name = scenarioName(opts.line, opts.sandbox, opts.sdk);

  // bundle 的 origin 经 esbuild define 烘焙：先起后端拿 origin，再回填 bundle 内容
  const holder = { js: '' };
  const backend = await startMockBackend(() => holder.js);
  holder.js = await buildSubAppBundle(opts.sdk, backend.origin);

  const hostContainer = document.createElement('div');
  hostContainer.id = `host-${name}`;
  document.body.appendChild(hostContainer);

  let hostClient: NlObserveClient | undefined;
  let hostExporter: InMemorySpanExporter | undefined;
  try {
    if (opts.sdk === 'host') {
      hostExporter = new InMemorySpanExporter();
      hostClient = new NlObserveClient();
      hostClient.init({
        serviceName: 'poc-micro-host',
        customerId: 'C-21',
        spanProcessors: [new SimpleSpanProcessor(hostExporter)],
      });
      hostClient.signIn('C-21');
      hostClient.startBusiness('ORDER');
      // 兜底桥接：host 模式下子应用经 'poc:track-rest'（bubbles）请求宿主 client 补记
      hostContainer.addEventListener('poc:track-rest', ((ev: Event) => {
        const d = (ev as CustomEvent<SubAppTrackRestDetail>).detail;
        hostClient?.trackRest(d.method, d.route, d.statusCode, d.durationMs);
      }) as EventListener);
    }

    await mountMicroApp(opts.line, {
      name,
      url: backend.appUrl,
      container: hostContainer,
      sandbox: opts.sandbox,
    });

    // 子应用就绪：元素 expando（跨沙箱共享真实 DOM，无事件竞态）
    const subEl = await waitFor(() => {
      const el = hostContainer.querySelector('#sub-app') as (HTMLElement & { __POC_READY__?: boolean }) | null;
      return el && el.__POC_READY__ ? el : false;
    });

    // host 模式基线：micro-app 挂载自身的资源 fetch（html + bundle js）也过宿主包装，
    // 记入基线 id 集合，阶段1 取差集——捕获率只数「子应用内 fetch」
    const baselineIds = new Set(
      opts.sdk === 'host' && hostExporter ? restDumps(hostExporter).map((s) => s.spanId) : [],
    );

    const phase1Promise = waitForEvent(subEl, 'poc:phase1');
    const donePromise = waitForEvent(subEl, 'poc:done');
    subEl.dispatchEvent(new CustomEvent('poc:go'));

    const phase1 = (await phase1Promise).detail as SubAppPhaseDetail;
    const autoSpans =
      opts.sdk === 'sub'
        ? (phase1.restSpans ?? [])
        : hostExporter
          ? restDumps(hostExporter).filter((s) => !baselineIds.has(s.spanId))
          : [];
    // 快照完成 → 放行阶段2（确定性握手，见 sub-app.ts 头注）
    subEl.dispatchEvent(new CustomEvent('poc:phase1-ack'));

    const done = (await donePromise).detail as SubAppPhaseDetail;
    const autoIds = new Set(autoSpans.map((s) => s.spanId));
    const fallbackSpans =
      opts.sdk === 'sub'
        ? (done.restSpans ?? []).filter((s) => !autoIds.has(s.spanId))
        : hostExporter
          ? restDumps(hostExporter).filter((s) => !autoIds.has(s.spanId) && !baselineIds.has(s.spanId))
          : [];

    const hostActive = opts.sdk === 'host' ? hostClient?.activeBusiness() : undefined;
    return {
      line: opts.line,
      sandbox: opts.sandbox,
      sdk: opts.sdk,
      fetchCount,
      autoCount: autoSpans.length,
      autoSpans,
      fallbackCount: fallbackSpans.length,
      fallbackSpans,
      // sub 模式 root 经沙箱内 activeBusiness() 随 detail 带出；host 模式宿主侧直接取
      rootSpanId: opts.sdk === 'host' ? hostActive?.span.spanContext().spanId : done.rootSpanId,
      rootTraceId: opts.sdk === 'host' ? hostActive?.span.spanContext().traceId : done.rootTraceId,
      fetchErrors: phase1.fetchErrors,
    };
  } finally {
    await unmountMicroApp(opts.line, name);
    await hostClient?.shutdown();
    await backend.stop();
    hostContainer.remove();
  }
}

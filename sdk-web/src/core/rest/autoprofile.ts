/**
 * REST 自动埋点 + 手动兜底（详设 §1.5 core/rest/autoprofile.ts；T14）。
 *
 * 包装全局 fetch 与 XMLHttpRequest 生成 REST span：
 * - name = 'HTTP {METHOD}'（方法大写）；SpanKind=CLIENT
 * - parent = 当前 ACTIVE 业务 root span（至多一个 ACTIVE 由 T13 Major-2 不变量保证）；
 *   无 ACTIVE 业务 → 不上报（不建 span，请求原样透传）
 * - 属性仅白名单 4 项（手工镜像 backend-observe-common AttributeKeys.REST_SPAN_WHITELIST，
 *   两侧改动必须同步）：http.request.method / http.route（去 query）/
 *   http.response.status_code / nl.duration_ms
 *   —— query 与 body 绝不进入 attributes（PII 白名单，质疑者2 R1；AC-04）
 * - trackRest 手动 API 与自动埋点共享 recordRestSpan 路径 → 行为一致（micro-app jsSandbox
 *   捕获不足时的兜底路径，T21 验收口径）
 * - 自排除：OTLP 接收端点自身的 fetch/XHR 不上报（否则 SDK 自己的 OTLP 导出会产生
 *   REST span → 递归放大）
 * - 网络失败（fetch reject / XHR status=0）仍记 span，status_code=0（OTel 未知状态口径）
 *
 * 安装/卸载：installRestInstrumentation 包装 globalThis.fetch 与 XMLHttpRequest.prototype
 * 的 open/send；uninstall 仅在「当前全局仍是本层包装」时还原（多客户端叠加时不破坏上层包装链）。
 */
import { ROOT_CONTEXT, SpanKind, trace, type Context, type Span, type Tracer } from '@opentelemetry/api';

/** REST span 属性：请求方法（AttributeKeys.HTTP_REQUEST_METHOD） */
export const ATTR_HTTP_REQUEST_METHOD = 'http.request.method';
/** REST span 属性：规范化路由，去 query（AttributeKeys.HTTP_ROUTE） */
export const ATTR_HTTP_ROUTE = 'http.route';
/** REST span 属性：响应状态码（AttributeKeys.HTTP_RESPONSE_STATUS_CODE） */
export const ATTR_HTTP_RESPONSE_STATUS_CODE = 'http.response.status_code';
/** REST span 属性：耗时 ms（AttributeKeys.NL_DURATION_MS） */
export const ATTR_NL_DURATION_MS = 'nl.duration_ms';

/** REST span 名称前缀：'HTTP {METHOD}' */
export const REST_SPAN_NAME_PREFIX = 'HTTP ';

export interface RestRecorderDeps {
  /** 显式 tracer（不经全局 register——POC 发现 4）；undefined → 不记 span */
  getTracer: () => Tracer | undefined;
  /** 当前 ACTIVE 业务 root span；undefined（无 ACTIVE 业务）→ 不上报 */
  getActiveRootSpan: () => Span | undefined;
  /** 可注入时钟（缺省 Date.now）；单测注入后 duration_ms 确定性断言 */
  now?: () => number;
  /** URL 排除谓词（OTLP 自身端点；缺省不排除） */
  isExcludedUrl?: (url: string) => boolean;
}

export interface RestSpanInput {
  method: string;
  /** 原始 URL 或 route（实现内规范化：去 query/origin） */
  route: string;
  /** 响应状态码；网络失败/未知传 0 */
  statusCode: number;
  durationMs: number;
  /** 请求开始时刻（自动埋点传真实起点；缺省按 now-durationMs 推导） */
  startTime?: number;
}

export interface RestInstrumentationHandle {
  /** 还原被包装的全局 fetch 与 XMLHttpRequest.prototype（幂等） */
  uninstall(): void;
}

function nowMs(deps: RestRecorderDeps): number {
  return deps.now ? deps.now() : Date.now();
}

function isExcluded(deps: RestRecorderDeps, url: string): boolean {
  return deps.isExcludedUrl ? deps.isExcludedUrl(url) : false;
}

/** 方法规范化：大写+trim；空值缺省 GET（fetch 语义）。 */
export function normalizeMethod(method: string): string {
  const m = String(method ?? '').trim().toUpperCase();
  return m || 'GET';
}

/**
 * 路由规范化（AC-04 去 query）：绝对 URL 仅留 path（origin/query/hash 不入 route，
 * 降基数+防 query PII）；相对 URL 去 ?query 与 #hash；空串归一为 '/'。
 */
export function normalizeRoute(rawUrl: string): string {
  const raw = String(rawUrl ?? '').trim();
  if (!raw) return '/';
  try {
    const parsed = new URL(raw);
    if (parsed.origin && parsed.origin !== 'null') {
      return parsed.pathname || '/';
    }
    // file: 等无 origin 协议：仅留 path 语义，防意外外泄
    return parsed.pathname && parsed.pathname !== '/' ? parsed.pathname : '/';
  } catch {
    // 相对 URL（new URL 无 base 会抛）→ 手工去 query/hash
    const cut = raw.split('?')[0].split('#')[0];
    return cut || '/';
  }
}

/**
 * 记 REST span（自动埋点与 trackRest 共用的唯一出口）：
 * parent=ACTIVE 业务 root span（同 traceId，parentSpanId=root spanId）；
 * 无 tracer / 无 ACTIVE 业务 → 静默 no-op（返回 false，与自动埋点「不上报」口径一致）。
 */
export function recordRestSpan(deps: RestRecorderDeps, input: RestSpanInput): boolean {
  const tracer = deps.getTracer();
  const parent = deps.getActiveRootSpan();
  if (!tracer || !parent) return false;
  const now = nowMs(deps);
  const method = normalizeMethod(input.method);
  const route = normalizeRoute(input.route);
  const durationMs = input.durationMs < 0 ? 0 : input.durationMs; // 负值不落（时钟回退/NTP，同 T13 口径）
  const startTime = input.startTime ?? now - durationMs;
  const ctx: Context = trace.setSpan(ROOT_CONTEXT, parent);
  const span = tracer.startSpan(
    REST_SPAN_NAME_PREFIX + method,
    {
      kind: SpanKind.CLIENT,
      startTime,
      attributes: {
        [ATTR_HTTP_REQUEST_METHOD]: method,
        [ATTR_HTTP_ROUTE]: route,
        [ATTR_HTTP_RESPONSE_STATUS_CODE]: input.statusCode,
        [ATTR_NL_DURATION_MS]: durationMs,
      },
    },
    ctx,
  );
  span.end(now);
  return true;
}

function extractFetchUrl(input: RequestInfo | URL): string | undefined {
  if (typeof input === 'string') return input;
  if (typeof URL !== 'undefined' && input instanceof URL) return input.href;
  if (typeof Request !== 'undefined' && input instanceof Request) return input.url;
  return undefined;
}

function extractFetchMethod(input: RequestInfo | URL, init?: RequestInit): string {
  const fromInit = typeof init?.method === 'string' && init.method.trim() !== '' ? init.method : undefined;
  const fromRequest =
    fromInit === undefined &&
    typeof Request !== 'undefined' &&
    input instanceof Request &&
    input.method.trim() !== ''
      ? input.method
      : undefined;
  return fromInit ?? fromRequest ?? 'GET';
}

/** XHR 实例级请求元数据（open 记录，send 消费；WeakMap 随实例 GC，不泄漏） */
interface XhrMeta {
  method: string;
  url: string;
}

/** 结构化 XHR 视图（避免与 lib.dom 签名漂移的兼容性问题） */
interface XhrLike {
  open: (...args: unknown[]) => void;
  send: (body?: unknown) => void;
  addEventListener?: (type: string, listener: EventListenerOrEventListenerObject) => void;
  /** 真实 XHR 按引用移除——finish 自注销依赖此语义（T14 评审 Major-1） */
  removeEventListener?: (type: string, listener: EventListenerOrEventListenerObject) => void;
  status?: number;
}

/**
 * 安装 REST 自动埋点：包装 globalThis.fetch 与 XMLHttpRequest.prototype.open/send。
 * 缺失的能力（如 node 环境无 XHR）自动跳过；uninstall 仅还原「当前仍是本层包装」的
 * 全局/原型方法（多客户端叠加时不破坏上层包装链）。
 */
export function installRestInstrumentation(deps: RestRecorderDeps): RestInstrumentationHandle {
  const g = globalThis as typeof globalThis & {
    fetch?: typeof globalThis.fetch;
    XMLHttpRequest?: unknown;
  };

  // ---- fetch 包装 ----
  let wrappedFetch: ((input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) | undefined;
  const originalFetch = g.fetch;
  if (typeof originalFetch === 'function') {
    wrappedFetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = extractFetchUrl(input);
      if (url === undefined || isExcluded(deps, url)) {
        return originalFetch(input, init);
      }
      // parent 取请求发起时刻的 ACTIVE 业务（业务在请求期间结束/切换不影响归属）
      const parent = deps.getActiveRootSpan();
      if (!parent) {
        return originalFetch(input, init);
      }
      const method = extractFetchMethod(input, init);
      const route = normalizeRoute(url);
      const startMs = nowMs(deps);
      return originalFetch(input, init).then(
        (res) => {
          recordRestSpan(deps, {
            method,
            route,
            statusCode: typeof res?.status === 'number' ? res.status : 0,
            durationMs: nowMs(deps) - startMs,
            startTime: startMs,
          });
          return res;
        },
        (err: unknown) => {
          recordRestSpan(deps, {
            method,
            route,
            statusCode: 0, // 网络错误无响应：状态码记 0（OTel 未知状态口径）
            durationMs: nowMs(deps) - startMs,
            startTime: startMs,
          });
          throw err;
        },
      );
    };
    g.fetch = wrappedFetch as typeof g.fetch;
  }

  // ---- XMLHttpRequest 包装（open 记元数据，send 挂钩 loadend 收尾） ----
  let xhrProto: XhrLike | undefined;
  let origOpen: XhrLike['open'] | undefined;
  let origSend: XhrLike['send'] | undefined;
  let wrappedOpen: XhrLike['open'] | undefined;
  let wrappedSend: XhrLike['send'] | undefined;
  const XHRCtor = g.XMLHttpRequest as (new () => XhrLike) | undefined;
  if (typeof XHRCtor === 'function' && XHRCtor.prototype) {
    const proto = XHRCtor.prototype as unknown as XhrLike;
    origOpen = proto.open;
    origSend = proto.send;
    const meta = new WeakMap<object, XhrMeta>();

    wrappedOpen = function (this: unknown, ...args: unknown[]): void {
      const methodArg = typeof args[0] === 'string' ? args[0] : '';
      const urlArg = typeof args[1] === 'string' ? args[1] : '';
      if (methodArg !== '' || urlArg !== '') {
        meta.set(this as object, { method: methodArg || 'GET', url: urlArg });
      }
      origOpen!.apply(this, args);
    } as XhrLike['open'];

    wrappedSend = function (this: XhrLike, body?: unknown): void {
      const m = meta.get(this as object);
      if (m && !isExcluded(deps, m.url)) {
        const parent = deps.getActiveRootSpan();
        if (parent) {
          const method = m.method;
          const route = normalizeRoute(m.url);
          const startMs = nowMs(deps);
          // finish 自注销：XHR 监听器跨 open/send 周期保留（规范），只增不删会使同实例
          // 第 N 次 send 触发 N 个监听器 → N 个错配 span（T14 评审 Major-1 实证：
          // 池化复用 XHR 污染 REST 时序）。自注销保证每次调用恰产生一个 span。
          const finish = () => {
            if (typeof this.removeEventListener === 'function') {
              this.removeEventListener('loadend', finish);
            }
            recordRestSpan(deps, {
              method,
              route,
              statusCode: typeof this.status === 'number' ? this.status : 0,
              durationMs: nowMs(deps) - startMs,
              startTime: startMs,
            });
          };
          if (typeof this.addEventListener === 'function') {
            this.addEventListener('loadend', finish);
          }
        }
      }
      origSend!.call(this, body);
    } as XhrLike['send'];

    proto.open = wrappedOpen;
    proto.send = wrappedSend;
    xhrProto = proto;
  }

  return {
    uninstall(): void {
      if (wrappedFetch && g.fetch === (wrappedFetch as typeof g.fetch)) {
        g.fetch = originalFetch;
      }
      if (xhrProto && origOpen && origSend) {
        if (wrappedOpen && xhrProto.open === wrappedOpen) xhrProto.open = origOpen;
        if (wrappedSend && xhrProto.send === wrappedSend) xhrProto.send = origSend;
      }
    },
  };
}

/**
 * T21 POC 子应用逻辑（vue3 + @nl-observe/sdk-web）。
 *
 * 双用：
 * 1) esbuild 打包 app/bundle-entry.ts 为 IIFE，由 micro-app 在（沙箱）内执行——捕获率场景；
 * 2) test/control.spec.ts 直接 import 本模块在测试进程驱动——无 micro-app 对照组
 *    （同时是本模块的单测面，避免打包产物拉低覆盖率）。
 *
 * 跨沙箱通信只用真实 DOM 元素级事件（micro-app 沙箱共享 document 与元素节点；
 * 元素事件不经 micro-app 的 window/document 事件 patch，信道最稳）：
 * - 就绪信号：容器元素 expando `__POC_READY__ = true`（真实 DOM 属性，跨沙箱可见，无竞态）
 * - 'poc:go'：阶段 1——自动埋点捕获：发 fetchCount 次 fetch，完成派发 'poc:phase1'；
 *   等测试侧快照后收到 'poc:phase1-ack' 才进阶段 2（确定性握手，防兜底 span 漏进自动快照）
 * - 阶段 2（go 内自动续）——手动兜底：对每次请求经 'poc:track-rest'（bubbles）信道补记
 *   trackRest，完成派发 'poc:done'
 * - 'poc:track-rest'：兜底载荷；监听方=sub 模式本应用 client / host 模式宿主 harness
 *   桥接宿主 client——「自动捕获不足时兜底可完整覆盖」（T21 验收 #2）
 * - 'poc:phase1'/'poc:done' detail：sub 模式带本沙箱导出器的 span dump 与业务 root
 *   spanId/traceId（activeBusiness() 活 span）；host 模式 dump 为 undefined（span 在
 *   宿主导出器，测试侧直接读）
 */
import { createApp, defineComponent, h } from 'vue';
import { createClient, type NlObserveClient } from '@nl-observe/sdk-web';
import {
  InMemorySpanExporter,
  SimpleSpanProcessor,
  type ReadableSpan,
} from '@opentelemetry/sdk-trace-base';

export interface SpanDump {
  name: string;
  traceId: string;
  spanId: string;
  /** OTel JS 2.x：经 parentSpanContext 暴露父（POC 发现 3）；root span 无父 → undefined */
  parentSpanId?: string;
  /** SpanKind 数值（OTel 枚举：INTERNAL=0/SERVER=1/CLIENT=2） */
  kind: number;
  attributes: Record<string, unknown>;
}

export interface SubAppTrackRestDetail {
  method: string;
  route: string;
  statusCode: number;
  durationMs: number;
}

export interface SubAppPhaseDetail {
  mode: 'sub' | 'host';
  /** sub 模式：本沙箱导出器内 REST span（阶段1=自动；done=自动+兜底全集）；host 模式 undefined */
  restSpans?: SpanDump[];
  /** sub 模式：业务 root span（activeBusiness() 活 span）；host 模式 undefined */
  rootSpanId?: string;
  rootTraceId?: string;
  fetchErrors: number;
}

export interface SubAppRunOptions {
  /** sub=子应用自初始化 SDK；host=宿主已初始化 SDK（本应用不 init，只用 fetch） */
  mode: 'sub' | 'host';
  /** mock 后端 origin（bundle 经 esbuild define 烘焙；测试进程直接传） */
  backendOrigin: string;
  /** 挂载点（真实 DOM 元素，宿主/沙箱两侧共享） */
  container: Element;
  /** 请求数（验收口径：10 次 fetch ≥9 次自动产生 REST span） */
  fetchCount?: number;
}

/** 最近一次 runSubApp 的 SDK 句柄（sub 模式；control 场景测试进程直接取用）。 */
let lastClient: NlObserveClient | undefined;
let lastExporter: InMemorySpanExporter | undefined;

export function getSubAppSdk(): { client: NlObserveClient; exporter: InMemorySpanExporter } | undefined {
  if (!lastClient || !lastExporter) return undefined;
  return { client: lastClient, exporter: lastExporter };
}

export function resetSubAppSdk(): void {
  lastClient = undefined;
  lastExporter = undefined;
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

/** REST span 识别（T14 口径：name='HTTP {METHOD}'）。 */
export function filterRestSpans(spans: SpanDump[]): SpanDump[] {
  return spans.filter((s) => s.name.startsWith('HTTP '));
}

function restDumps(): SpanDump[] {
  if (!lastExporter) return [];
  return filterRestSpans(lastExporter.getFinishedSpans().map(toDump));
}

/**
 * 运行子应用：挂载 vue3 组件 →（sub 模式）初始化 SDK 并签入/起业务 →
 * 监听 'poc:go'（阶段1 自动捕获 + 阶段2 兜底覆盖）。
 */
export function runSubApp(opts: SubAppRunOptions): void {
  const container = opts.container;
  const fetchCount = opts.fetchCount ?? 10;

  if (opts.mode === 'sub') {
    const exporter = new InMemorySpanExporter();
    const client = createClient({
      serviceName: 'poc-micro-sub',
      customerId: 'C-21',
      // 沙箱内 window=micro-app proxyWindow，非真实 EventTarget——SDK 的 flush/首屏
      // window 监听显式关闭（windowLike/performanceObserver=null，SDK 支持的离线口径）；
      // POC 用 InMemory 导出器，无 flush 需求（实测：不关则 init 在沙箱内抛
      // "'addEventListener' called on an object that is not a valid instance of EventTarget"）
      windowLike: null,
      performanceObserver: null,
      spanProcessors: [new SimpleSpanProcessor(exporter)],
    });
    client.signIn('C-21');
    client.startBusiness('ORDER');
    lastClient = client;
    lastExporter = exporter;
    // 兜底信道：sub 模式由本应用 client 消费
    container.addEventListener('poc:track-rest', ((ev: Event) => {
      const d = (ev as CustomEvent<SubAppTrackRestDetail>).detail;
      lastClient?.trackRest(d.method, d.route, d.statusCode, d.durationMs);
    }) as EventListener);
  }

  const App = defineComponent({
    name: 'PocSubApp',
    setup() {
      return () => h('div', { id: 'sub-root' }, 'poc sub app');
    },
  });
  createApp(App).mount(container);

  const settle = (): Promise<void> => new Promise((r) => setTimeout(r, 50));

  container.addEventListener('poc:go', (() => {
    void (async () => {
      let fetchErrors = 0;
      const trackPayloads: SubAppTrackRestDetail[] = [];
      for (let i = 0; i < fetchCount; i++) {
        const startedAt = Date.now();
        let statusCode = 0;
        try {
          const res = await fetch(`${opts.backendOrigin}/api/ping?i=${i}`);
          statusCode = res.status;
        } catch {
          fetchErrors += 1;
        }
        trackPayloads.push({
          method: 'GET',
          route: '/api/ping',
          statusCode,
          durationMs: Date.now() - startedAt,
        });
      }
      await settle(); // 自动埋点 span 在 fetch resolve 回调内落导出器
      const active = opts.mode === 'sub' ? lastClient?.activeBusiness() : undefined;
      container.dispatchEvent(
        new CustomEvent('poc:phase1', {
          detail: {
            mode: opts.mode,
            restSpans: opts.mode === 'sub' ? restDumps() : undefined,
            rootSpanId: active?.span.spanContext().spanId,
            rootTraceId: active?.span.spanContext().traceId,
            fetchErrors,
          } satisfies SubAppPhaseDetail,
        }),
      );
      // 确定性快照握手：dispatchEvent 同步返回后本 async 函数会继续跑（测试侧 await 的微任务
      // 排在其后）——不等 ack 则阶段2 的兜底 span 会漏进测试侧「自动」快照（host 模式实测竞态）。
      // 测试侧收 phase1 → 取快照 → 派发 'poc:phase1-ack'（真实元素事件，跨沙箱可达）。
      await new Promise<void>((resolve) => {
        container.addEventListener('poc:phase1-ack', () => resolve(), { once: true });
      });
      // 阶段2：兜底覆盖——逐条经信道补记 trackRest（与自动捕获结果无关，恒发）
      for (const p of trackPayloads) {
        container.dispatchEvent(new CustomEvent('poc:track-rest', { detail: p, bubbles: true }));
      }
      await settle();
      container.dispatchEvent(
        new CustomEvent('poc:done', {
          detail: {
            mode: opts.mode,
            restSpans: opts.mode === 'sub' ? restDumps() : undefined,
            rootSpanId: active?.span.spanContext().spanId,
            rootTraceId: active?.span.spanContext().traceId,
            fetchErrors,
          } satisfies SubAppPhaseDetail,
        }),
      );
    })();
  }) as EventListener);

  // 就绪信号：元素 expando（真实 DOM，跨沙箱共享，测试侧轮询无竞态）
  (container as HTMLElement & { __POC_READY__?: boolean }).__POC_READY__ = true;
}

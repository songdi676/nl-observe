/**
 * T23 本地离线自检 stub 后端（非验收环境！内网可达时 E2E 必须指向真实
 * receiver/query——详设 §6.4 env-gate 口径不变）。
 *
 * 单进程两端口，模拟全链路可观测面，供离线环境跑通 Playwright 用例逻辑：
 * - OTLP 入口（默认 4318，对齐 receiver observe.otlp.port）：POST /v1/traces、
 *   /v1/metrics、/v1/logs（OTLP/HTTP JSON，SDK sendBeacon 文本口径）→ 内存存储
 * - 查询 API（默认 8081，对齐 query 服务）：详设 §2 四接口 + §4.1 错误码信封
 *   （40001 参数缺失 / 40401 无数据）；effective_duration_ms 取 span 属性
 *   biz.effective_duration_ms（对齐 storage OtlpJsonParser 口径）
 *
 * 端口：E2E_STUB_OTLP_PORT / E2E_STUB_QUERY_PORT。GET /healthz 就绪探针。
 */
import http from 'node:http';

const OTLP_PORT = Number(process.env.E2E_STUB_OTLP_PORT ?? 4318);
const QUERY_PORT = Number(process.env.E2E_STUB_QUERY_PORT ?? 8081);

/** OTLP 请求体上限（评审 sec Info-4）：本机自检工具，超限直接 413 拒收 */
const MAX_BODY_BYTES = 8 * 1024 * 1024;

/** 数值字段守卫（评审 sec L-3）：畸形 OTLP 载荷的 NaN/Infinity 在入库时归 0，
 * 避免查询期 new Date(NaN).toISOString() 抛 RangeError 拖垮整个 stub 进程 */
function toFiniteNumber(v, fallback = 0) {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

/** @type {{customer: string, traceId: string, spanId: string, parentSpanId: string, name: string, kind: number, startNs: number, endNs: number, statusCode: number, attributes: Record<string, unknown>, events: {name: string}[]}[]} */
const spans = [];
/** @type {{customer: string, metricName: string, timeNs: number, value: number, attributes: Record<string, unknown>}[]} */
const metricPoints = [];

// ---- OTLP/JSON 解析（protobuf JSON 映射，对齐 common OtlpTracePayload/OtlpMetricPayload）----
function valueToPrim(v) {
  if (!v || typeof v !== 'object') return undefined;
  if ('stringValue' in v) return v.stringValue;
  if ('intValue' in v) return Number(v.intValue);
  if ('doubleValue' in v) return v.doubleValue;
  if ('boolValue' in v) return v.boolValue;
  if ('arrayValue' in v) return (v.arrayValue.values ?? []).map(valueToPrim);
  return undefined;
}

function attrMap(list) {
  const m = {};
  for (const kv of list ?? []) m[kv.key] = valueToPrim(kv.value);
  return m;
}

function resourceAttrs(res) {
  return attrMap(res?.attributes);
}

function ingestTraces(body) {
  for (const rs of body.resourceSpans ?? []) {
    const res = resourceAttrs(rs.resource);
    for (const ss of rs.scopeSpans ?? []) {
      for (const s of ss.spans ?? []) {
        spans.push({
          customer: String(res['nl.customer_id'] ?? ''),
          traceId: s.traceId,
          spanId: s.spanId,
          parentSpanId: s.parentSpanId ?? '',
          name: s.name,
          kind: toFiniteNumber(s.kind ?? 0),
          startNs: toFiniteNumber(s.startTimeUnixNano ?? 0),
          endNs: toFiniteNumber(s.endTimeUnixNano ?? 0),
          statusCode: toFiniteNumber(s.status?.code ?? 0),
          attributes: attrMap(s.attributes),
          events: (s.events ?? []).map((e) => ({ name: e.name })),
        });
      }
    }
  }
}

function ingestMetrics(body) {
  for (const rm of body.resourceMetrics ?? []) {
    const res = resourceAttrs(rm.resource);
    for (const sm of rm.scopeMetrics ?? []) {
      for (const m of sm.metrics ?? []) {
        const dps = m.sum?.dataPoints ?? m.gauge?.dataPoints ?? [];
        for (const dp of dps) {
          const raw = dp.asDouble ?? dp.asInt;
          metricPoints.push({
            customer: String(res['nl.customer_id'] ?? ''),
            metricName: m.name,
            timeNs: toFiniteNumber(dp.timeUnixNano ?? 0),
            value: toFiniteNumber(raw ?? 0),
            attributes: attrMap(dp.attributes),
          });
        }
      }
    }
  }
}

// ---- 查询 API（详设 §2 契约镜像）----
const envelope = (code, message, data) => ({ code, message, data });

function parseDate(s) {
  return /^\d{4}-\d{2}-\d{2}$/.test(s ?? '') ? s : null;
}

function utcDateOf(ns) {
  return new Date(ns / 1e6).toISOString().slice(0, 10);
}

function inWindow(ns, from, to) {
  // 对齐真实后端（评审 corr Minor-2）：QueryRepository 的 toExclusive = to 次日
  // 0 点（UTC），即 to 当日整天包含在内——日期粒度下等价于 d <= to
  const d = utcDateOf(ns);
  return d >= from && d <= to;
}

function p95(values) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((x, y) => x - y);
  const idx = Math.max(0, Math.ceil(0.95 * sorted.length) - 1);
  return sorted[idx];
}

function bizRoots(customer, from, to, bizCode) {
  return spans
    .filter(
      (s) =>
        s.name.startsWith('biz.business.') &&
        (customer === '' || s.customer === customer) &&
        inWindow(s.startNs, from, to) &&
        (bizCode === '' || String(s.attributes['biz.code'] ?? '') === bizCode),
    )
    .sort((x, y) => y.startNs - x.startNs);
}

function handleQuery(req, res, url) {
  const json = (status, payload) => {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(payload));
  };
  if (url.pathname === '/healthz') return json(200, { ok: true });

  const custMatch = url.pathname.match(/^\/api\/observe\/customers\/([^/]+)\/(businesses|traces(?:\/([^/]+))?)?$/);
  const metricsMatch = url.pathname === '/api/observe/metrics/summary';

  if (custMatch) {
    // 畸形百分号编码（如 %zz）会抛 URIError——不捕获则整个 stub 进程崩溃
    // （评审 sec L-2）；按参数缺失口径回 400/40001
    let customerId;
    try {
      customerId = decodeURIComponent(custMatch[1]);
    } catch {
      return json(400, envelope(40001, '路径参数百分号编码非法', null));
    }

    if (custMatch[2] === 'businesses') {
      const from = parseDate(url.searchParams.get('from'));
      const to = parseDate(url.searchParams.get('to'));
      if (!from || !to) return json(400, envelope(40001, 'from/to 必填（YYYY-MM-DD）', null));
      const bizCode = url.searchParams.get('bizCode') ?? '';
      const items = bizRoots(customerId, from, to, bizCode).map((s) => ({
        bizId: s.spanId,
        bizCode: String(s.attributes['biz.code'] ?? s.name.slice('biz.business.'.length)),
        traceId: s.traceId,
        startTime: new Date(s.startNs / 1e6).toISOString(),
        effectiveDurationMs: Number(s.attributes['biz.effective_duration_ms'] ?? 0),
        status: String(s.attributes['biz.status'] ?? ''),
        endReason: String(s.attributes['biz.end_reason'] ?? ''),
      }));
      if (items.length === 0) return json(404, envelope(40401, '无数据', null));
      return json(200, envelope(0, 'ok', items.slice(0, 200)));
    }

    const traceId = custMatch[3];
    if (traceId) {
      const rows = spans
        .filter((s) => s.customer === customerId && s.traceId === traceId)
        .sort((x, y) => x.startNs - y.startNs);
      if (rows.length === 0) return json(404, envelope(40401, '无数据', null));
      return json(
        200,
        envelope(0, 'ok', {
          traceId,
          spans: rows.map((s) => ({
            spanId: s.spanId,
            parentSpanId: s.parentSpanId,
            name: s.name,
            kind: s.kind,
            startTime: new Date(s.startNs / 1e6).toISOString(),
            endTime: new Date(s.endNs / 1e6).toISOString(),
            statusCode: s.statusCode,
            durationMs: (s.endNs - s.startNs) / 1e6,
            effectiveDurationMs: Number(s.attributes['biz.effective_duration_ms'] ?? 0),
            attributes: s.attributes,
            events: s.events,
          })),
        }),
      );
    }
    return json(404, envelope(40401, '无数据', null));
  }

  if (metricsMatch) {
    const from = parseDate(url.searchParams.get('from'));
    const to = parseDate(url.searchParams.get('to'));
    if (!from || !to) return json(400, envelope(40001, 'from/to 必填（YYYY-MM-DD）', null));
    const customerId = url.searchParams.get('customerId') ?? '';
    const bizCode = url.searchParams.get('bizCode') ?? '';

    const firstScreen = metricPoints
      .filter(
        (p) =>
          p.metricName === 'nl.first_screen_load' &&
          (customerId === '' || p.customer === customerId) &&
          inWindow(p.timeNs, from, to),
      )
      .map((p) => p.value);

    const perBiz = [];
    for (const s of bizRoots(customerId, from, to, bizCode)) {
      const code = String(s.attributes['biz.code'] ?? '');
      let row = perBiz.find((r) => r.bizCode === code);
      if (!row) {
        row = { bizCode: code, values: [], success: 0, count: 0 };
        perBiz.push(row);
      }
      row.values.push(Number(s.attributes['biz.effective_duration_ms'] ?? 0));
      row.count += 1;
      if (s.attributes['biz.status'] === 'success') row.success += 1;
    }
    const firstScreenP95 = p95(firstScreen);
    const perBizRows = perBiz
      .map((r) => ({
        bizCode: r.bizCode,
        effectiveDurationP95: p95(r.values) ?? 0,
        successRate: r.count ? r.success / r.count : 0,
        count: r.count,
      }))
      .sort((x, y) => x.bizCode.localeCompare(y.bizCode));
    // 对齐真实后端（评审 corr Minor-2）：MetricSummaryService 两指标皆空时抛
    // NOT_FOUND——stub 此前返回 200+null 属契约镜像漂移，会误导自检排障
    if (firstScreenP95 === null && perBizRows.length === 0) {
      return json(404, envelope(40401, '无数据', null));
    }
    return json(200, envelope(0, 'ok', {
      firstScreenLoadP95: firstScreenP95,
      perBiz: perBizRows,
    }));
  }

  return json(404, envelope(40401, '未知路径', null));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        tooLarge = true; // 继续排空流但不再留存（评审 sec Info-4：内存无界增长兜底）
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (tooLarge) reject(new Error('BODY_TOO_LARGE'));
      else resolve(Buffer.concat(chunks).toString('utf8'));
    });
    req.on('error', reject);
  });
}

const otlpServer = http.createServer(async (req, res) => {
  // CORS：真实接收端（collector-embedded 1.2.1）无 CORS——E2E 浏览器以
  // --disable-web-security 直发；stub 额外支持 CORS 使本地自检不依赖该开关
  res.setHeader('access-control-allow-origin', '*');
  if (req.method === 'OPTIONS') {
    res.setHeader('access-control-allow-methods', 'POST, OPTIONS');
    res.setHeader('access-control-allow-headers', 'content-type');
    res.writeHead(204).end();
    return;
  }
  if (req.method !== 'POST') {
    res.writeHead(405).end();
    return;
  }
  let raw;
  try {
    raw = await readBody(req);
  } catch (err) {
    if (err.message === 'BODY_TOO_LARGE') {
      res.writeHead(413, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ code: 40001, message: '请求体超 8MiB 上限', data: null }));
    } else {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ code: 40001, message: '请求体读取失败', data: null }));
    }
    return;
  }
  try {
    const body = JSON.parse(raw);
    if (req.url.startsWith('/v1/traces')) ingestTraces(body);
    else if (req.url.startsWith('/v1/metrics')) ingestMetrics(body);
    // logs：接收即可（E2E 不断言日志链路）
  } catch (err) {
    console.error('[stub] OTLP 解析失败:', err.message);
  }
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ partialSuccess: {} }));
});

const queryServer = http.createServer((req, res) => {
  let url;
  try {
    url = new URL(req.url ?? '/', 'http://127.0.0.1');
  } catch {
    res.writeHead(400).end(); // 畸形请求行不崩进程（与 sec L-2 同类健壮性）
    return;
  }
  handleQuery(req, res, url);
});

// 端口被占（如上次 run-local 被 SIGKILL 遗留的孤儿 stub）时以非零码显式退出，
// 而不是未捕获 EADDRINUSE 崩溃（评审 arch m-5）
function listenFatal(name) {
  return (err) => {
    console.error(`[stub] ${name} 监听失败（端口被占？）：${err.message}`);
    process.exit(1);
  };
}
otlpServer.on('error', listenFatal('OTLP 入口'));
queryServer.on('error', listenFatal('查询 API'));

otlpServer.listen(OTLP_PORT, '127.0.0.1', () => {
  console.log(`[stub] OTLP 入口 http://127.0.0.1:${OTLP_PORT}/v1/{traces,metrics,logs}`);
});
queryServer.listen(QUERY_PORT, '127.0.0.1', () => {
  console.log(`[stub] 查询 API http://127.0.0.1:${QUERY_PORT}/api/observe（GET /healthz 探针）`);
});

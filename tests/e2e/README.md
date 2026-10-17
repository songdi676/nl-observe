# tests/e2e — Playwright 全链路 E2E（T23，env-gated）

详设 §6.4：真实浏览器（vue3 宿主页）办理业务全流程 → SDK OTLP/HTTP 上报 →
receiver → Kafka → storage → Databend → 查询 API 断言。内网 Kafka/Databend
可达性未知，故 env-gated：`E2E_BACKEND_URL` 未设置时全部 skip。

## 文件

| 文件 | 作用 |
|---|---|
| `full-chain.spec.ts` | 签入→业务 A（真实 fetch 至 mock 后端）→切 B（A 挂起）→恢复并结束 A→结束 B→查询断言（2 业务、traceId 不同、REST span 归属、effectiveDurationMs 宽松区间） |
| `metrics.spec.ts` | `nl.first_screen_load` data point 存在性（不断言精确值） |
| `host/` | vue3 测试宿主页（vite dev + `/mock-api` mock 后端中间件；SDK 经 alias 引 `sdk-web/src` 源码） |
| `support/e2e-env.ts` | env-gate、查询 API 客户端、轮询助手 |
| `support/stub-backend.mjs` | 本地离线自检 stub（OTLP 入口 + 查询 API 契约镜像，非验收环境） |
| `support/run-local.mjs` | 离线自检入口（`npm run test:e2e:local`） |

## 运行

```bash
# 内网全链路（验收口径）：查询服务基址 + （可选）OTLP 端点覆盖
E2E_BACKEND_URL=http://10.1.8.33:8081 npm run test:e2e
# OTLP 端点缺省按 E2E_BACKEND_URL 同主机端口 4318 派生；E2E_OTLP_URL 可覆盖

# 离线自检（无内网）：stub 后端 + 同一套 spec
npm run test:e2e:local
```

宿主页由 `playwright.config.ts` 的 webServer 自动拉起（127.0.0.1:4173，
仅 env-gate 打开时）。首次需 `npx playwright install chromium`。

## 已知后端缺口（CORS）

接收端（collector-embedded 1.2.1 + OtlpSecurityFilter）不处理 OPTIONS、不发
`Access-Control-*` 头；SDK `sendBeacon` 的 `application/json` Blob 在跨源浏览器
会触发 preflight 被拦（chromium 156 实测）。E2E 浏览器以 `--disable-web-security`
直发载荷（口径与接收端 `isJsonContent` 判定一致）；生产跨源部署需同源代理或
接收端补 CORS——该缺口属后端（T06 侧）范畴，不在 T23 修复。

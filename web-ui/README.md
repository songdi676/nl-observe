# web-ui

NL Observe 前端透明化可视化工程（Vue3 + Vite + TS）。T18 交付骨架：

- 路由三页：`/businesses` 业务列表、`/traces/:traceId?` 业务流程时间线、`/metrics` 性能指标
- `src/api/observeApi.ts`：封装 `/api/observe/*`（详细设计 §2 四接口），统一解包 `{code,message,data}`，非 0 code 抛 `ApiError`
- 401 未授权 → 跳转登录（外部网关依赖，配置项 `VITE_LOGIN_URL`，见 `.env.example`）

## 命令

```bash
npm run dev            # 本地开发（/api 代理到 http://localhost:8080）
npm run build          # 生产构建
npm run type-check     # vue-tsc 类型检查
npm run lint           # ESLint
npm run test           # vitest 单测（msw mock）
npx vitest run --coverage   # 覆盖率（质量门产物 coverage/coverage-summary.json）
```

## 质量门

在本目录运行 `bash <repo>/scripts/quality-gate.sh --scope frontend`（eslint / vue-tsc / npm audit / vitest 覆盖率 ≥70%）。

## 后续任务

- T19：业务列表 + span 树时间线（本页面对应 API 就绪后接线）
- T20：指标卡片

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

## 已交付页面

- T19：BusinessesPage 业务列表 + TracePage span 树时间线（挂起/恢复事件标记、span 详情抽屉）
- T20：MetricsPage 指标卡片——调用 GET /api/observe/metrics/summary（customerId 必填，类型钉死，
  规避运维视图限流桶；恒带 customerId），展示 firstScreenLoadP95 与 perBiz{effectiveDurationP95,
  successRate, count}；完全无数据（后端 40401）与字段级空值均展示占位态，其余错误态展示错误码
  （不逐字展示后端 message，评审 sec-L-1）+ 重试按钮（按上次查询参数快照重发）

## 后续任务

- 筛选表单（日期范围 + 查询按钮）与 .filters/.query-btn/.error/.empty/.loading 样式在
  BusinessesPage 与 MetricsPage 间复制（约 70 行）——出现第三个查询页时应抽共享
  `DateRangeFilter` 组件/共享样式块（T20 评审 arch M-2，暂记跟进项）

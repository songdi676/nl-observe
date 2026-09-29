---
feature: 前端透明化建设
design: docs/designs/2026-09-28-前端透明化建设-design.md
detail_design: docs/plans/前端透明化建设/详细设计.md
tasks:
  - id: T01
    title: "POC：collector-embedded 1.2.1 与 OTel 前端依赖验证"
    deps: []
    acceptance:
      - "collector-embedded 1.2.1 在 Spring Boot 3 / JDK17 进程内启动，OTLP/HTTP 4318 可接收 ExportTraceServiceRequest 并成功写入 Testcontainers Kafka 的 nl-observe.otlp.traces"
      - "确认 collector 导出失败时的可观测钩子（回调或 producer 异常计数点），产出结论记录于 poc-notes.md"
      - "@opentelemetry/sdk-web 及 otlp-http exporter 在 vue2 与 vue3 测试宿主均可初始化并导出 span（版本锁定记录）"
      - "内网 Maven/npm 仓库依赖可得性确认（不可得则给出替代版本并记录）"
    test_cases:
      - "poc/receiver-otlp-kafka: 发样例 OTLP/HTTP → 断言 topic 收到消息"
      - "poc/sdk-vue2-init / poc/sdk-vue3-init: 双宿主 InMemorySpanExporter 断言 span 导出"
    description: "关键路径前置。验证概要设计风险 #1/#7，锁定 OTel SDK 与 collector 版本；产出 poc-notes.md 供 T04/T12 选型。"
  - id: T02
    title: "backend-observe-common 模块（DDL 常量/DTO/OTLP 模型/统一响应/错误码）"
    deps: []
    acceptance:
      - "父 POM（SB3、JDK17、checkstyle P3C/spotbugs/jacoco 配置）与 common 模块编译通过"
      - "DdlScripts 含三表完整 DDL（与详细设计 §3/概要设计 §6 一致，ReplacingMergeTree+分区+TTL）"
      - "AttributeKeys/Topics 常量覆盖 nl.*/biz.*/http.* 白名单与三 topic 名"
      - "ApiResult<T> 与 ErrorCode（0/40001/40301/40401/41301/42901/50000）可用"
    test_cases:
      - "common 单元测试：OtlpTracePayload 反序列化样例 JSON 字段正确"
      - "common 单元测试：ApiResult 序列化结构 {code,message,data}"
    description: "三后端共享契约（质疑者1 R1 意见落实：单向依赖、彼此不互依）。"
  - id: T03
    title: "Databend 建表脚本 scripts/databend/001-init.sql"
    deps: [T02]
    acceptance:
      - "001-init.sql 由 DdlScripts 生成，含 otel_spans/otel_metrics/otel_logs 三表（ReplacingMergeTree、ORDER BY 与去重身份一致、日分区、TTL 90 天）"
      - "脚本在 Testcontainers Databend（或人工环境）执行成功"
    test_cases:
      - "集成测试：Testcontainers Databend 执行 001-init.sql 无错，SHOW TABLES 含三表"
    description: "存储端与查询端的前置。"
  - id: T04
    title: "receiver：Spring Boot 骨架 + 内嵌 collector-embedded + OTLP/HTTP 4318"
    deps: [T01, T02]
    acceptance:
      - "应用启动后 OTLP/HTTP 4318（端口 observe.otlp.port 可配）可接收 /v1/traces 与 /v1/metrics；未启用 gRPC 4317"
      - "collector 随 SmartLifecycle 启动/停止，应用关闭无端口泄漏"
    test_cases:
      - "集成测试：随机端口启动 → POST 样例 traces/metrics → 200"
      - "集成测试：上下文关闭后端口释放"
    description: "US-07/AC-06 前半。嵌入方式与版本按 T01 poc-notes。"
  - id: T05
    title: "receiver：Kafka 三 topic 写入 + /config/producer + 丢弃计数指标"
    deps: [T04]
    acceptance:
      - "traces/metrics/logs 路由至 nl-observe.otlp.{traces,metrics,logs}，载荷 OTLP/JSON，key=nl.customer_id"
      - "producer 参数与需求 yaml 完全一致（acks=all、idempotence、lz4、linger 5ms、batch 64KB、delivery 20s、request 15s、retries 无限、max-in-flight 5、max-block 5s、buffer 64MB、send-timeout 9s）"
      - "GET /config/producer 返回生效参数（测试可断言）；Kafka 不可达时 5s 内快速失败、返回 200 且 nl.receiver.dropped 递增（AC-10）"
    test_cases:
      - "集成测试：发样例 OTLP → 三 topic 消息体/key 断言"
      - "集成测试：/config/producer 参数与 yaml 逐项相等"
      - "故障注入：停 Kafka 容器 → 断言 200 + dropped 计数递增"
    description: "US-07/AC-06/AC-10/NFR-01/NFR-02。"
  - id: T06
    title: "receiver：安全过滤器（IP 白名单 + 请求体上限 + 限流）"
    deps: [T05]
    acceptance:
      - "非白名单 CIDR 请求 → 403；请求体 > max-request-bytes → 413；超 rate-limit-per-min → 429"
      - "白名单内正常流量不受影响"
    test_cases:
      - "集成测试：mock 非白名单源 IP → 403"
      - "集成测试：11MB body → 413；超频 → 429；正常 → 200"
    description: "安全设计（质疑者2 R1）：入口防伪造/DoS。"
  - id: T07
    title: "storage：Spring Boot 骨架 + 三 Kafka consumer"
    deps: [T02, T03]
    acceptance:
      - "三独立 listener（traces/metrics/logs），消费组 nl-observe-storage，批处理 batch.listener=true"
      - "写库成功后才提交 offset（at-least-once）；写失败重试 3 次后 error 日志 + 跳过该批不提交"
    test_cases:
      - "集成测试：灌消息 → 消费位点推进；repository mock 抛错 → 重试 3 次后跳过、offset 不提交"
    description: "US-08 消费侧骨架。"
  - id: T08
    title: "storage：OTLP 解析 + 三表 JDBC batch 写入 + 启动自检"
    deps: [T07]
    acceptance:
      - "OTLP/JSON 解析为行模型：spans 提取 customer_id/biz_code/effective_duration_ms 并保留 resource/scope/attributes JSON；metrics 展开 data point 计算 dp_hash；logs 计算 row_hash"
      - "JDBC 命名参数 batch insert（500/批），无字符串拼接"
      - "启动自检 SELECT 1 失败即启动失败；连接串缺 presigned_url_disabled=true 时自检报错（NFR-04）"
    test_cases:
      - "单元测试：OtlpJsonParser 样例 → 行模型/dp_hash/row_hash 稳定且区分不同数据点"
      - "集成测试（Testcontainers Databend，env-gated 可降级 mock）：灌样例 → 三表行数/字段断言；同 span 灌两次 → FINAL 查询 1 行"
      - "单元测试：连接串缺参 → 自检抛错"
    description: "US-08/AC-07。去重身份=ORDER BY 键（质疑者1 R2 落实）。"
  - id: T09
    title: "query：Spring Boot 骨架 + Databend 只读连接 + 审计日志"
    deps: [T02, T03]
    acceptance:
      - "只读账号连接 Databend（同 presigned_url_disabled 自检）；JdbcTemplate 命名参数，代码评审无 SQL 拼接"
      - "AuditLogFilter 对 /api/observe/** 记录 method/path/customerId/traceId 至独立 audit 日志文件"
    test_cases:
      - "集成测试：调用任一 API → audit 日志含对应行"
      - "单元测试：QueryRepository SQL 模板断言（命名参数占位）"
    description: "US-09 骨架；安全（参数化 A03 + 审计 + 只读账号分离）。"
  - id: T10
    title: "query：业务列表 / span 树 / span 详情 API"
    deps: [T09]
    acceptance:
      - "GET /customers/{cid}/businesses?from&to&bizCode → 业务列表（bizId/bizCode/traceId/startTime/effectiveDurationMs/status/endReason），from/to 必填、跨度>31 天→40001、无数据→40401"
      - "GET /customers/{cid}/traces/{tid} → span 树（FINAL 查询，含 REST span 白名单属性与 events）"
      - "GET /customers/{cid}/traces/{tid}/spans/{sid} → 单 span 全字段（JSON 字段解析为对象）"
      - "统一 {code,message,data} 响应"
    test_cases:
      - "集成测试：预置数据 → 三接口数据断言；参数错误/无数据 → 错误码断言"
      - "tests/api/observe-query.collection.json：businesses/trace/span 正常+异常用例（Newman）"
    description: "US-09/AC-08（前半）/AC-09 数据支撑。"
  - id: T11
    title: "query：/metrics/summary API（quantile P95 + 成功率 + 运维视图限流）"
    deps: [T10]
    acceptance:
      - "GET /metrics/summary?from&to&bizCode&customerId：customerId 指定→客户作用域；缺省→跨客户运维视图（60 次/分限流，超限 42901）"
      - "返回 firstScreenLoadP95（quantile(0.95)）与 perBiz{effectiveDurationP95, successRate, count}；successRate=sum(biz_status='success')/count"
    test_cases:
      - "集成测试：已知数据点 → P95/成功率与手工计算对拍"
      - "集成测试：不带 customerId 超频 → 42901"
      - "tests/api/observe-query.collection.json：metrics-summary 用例（Newman）"
    description: "US-06/US-09/AC-08（后半）。客户作用域/运维视图语义为质疑者1 R2 落实。"
  - id: T12
    title: "sdk-web 包骨架 + vue2/vue3 双构建"
    deps: [T01]
    acceptance:
      - "npm 包 @nl-observe/sdk-web：入口 index.ts（框架无关 core）+ vue2.ts + vue3.ts，es/cjs 双格式构建成功"
      - "peerDependencies：vue2 入口 vue@^2、vue3 入口 vue@^3；OTel 依赖版本按 T01 锁定"
      - "vue2/vue3 两个测试宿主（Vitest+jsdom+@vue/test-utils v1/v2）可跑通空用例"
    test_cases:
      - "构建产物测试：require(esm/cjs) 均可加载"
      - "vue2-host / vue3-host 冒烟测试"
    description: "US-01/US-02/NFR-03 工程基础（质疑者1 R1 双构建成本对策：共享 core）。"
  - id: T13
    title: "sdk core：SigninManager + BusinessManager 状态机（挂起/恢复/有效耗时）"
    deps: [T12]
    acceptance:
      - "sign_in 互斥：已签入再签入先隐式 sign_out（warn）；sign_out 后 startBusiness no-op+warn"
      - "startBusiness(bizCode) 生成独立 root span（name=biz.business.<bizCode>，不同业务 traceId 不同）并自动挂起其他 ACTIVE 业务（span event biz.suspend_start，span 不结束）"
      - "resume 记 biz.resume event；end 写 biz.total_suspended_ms、biz.effective_duration_ms（=墙钟-Σ挂起）、biz.status、biz.end_reason（explicit|unmount）并发 nl.business_duration data point"
      - "endBusiness({status:'success'|'fail'}) 为成败唯一依据；unmount 未显式结束计 fail（end_reason=unmount）"
    test_cases:
      - "单测（InMemorySpanExporter）：签入互斥用例"
      - "单测：A、B 双业务 traceId 不同；B 启动后 A 有 biz.suspend_start 且 A root span 未 end"
      - "单测：挂起 100ms 后恢复，effective_duration_ms = 墙钟-100ms（确定性断言）"
      - "单测：endBusiness fail → biz.status=fail；unmount 未 end → end_reason=unmount 且 status=fail"
    description: "US-04/AC-03/AC-05 核心语义（质疑者1 R1 有效耗时语义落实）。"
  - id: T14
    title: "sdk core：fetch/XHR 自动埋点 + trackRest 手动兜底"
    deps: [T13]
    acceptance:
      - "fetch 与 XMLHttpRequest 调用生成 REST span（name='HTTP {method}'，parent=当前 ACTIVE 业务 root span）；无 ACTIVE 业务时不上报"
      - "属性仅白名单：http.request.method、http.route（去 query）、http.response.status_code、nl.duration_ms；query/body 不进入 attributes"
      - "trackRest(method, route, statusCode, durationMs) 手动 API 行为与自动埋点一致（沙箱兜底）"
    test_cases:
      - "单测：fetch 成功/失败均产生 REST span 且 parent 正确；无 ACTIVE 业务无 span"
      - "单测：URL 含 query → http.route 已去 query；attributes 键集合=白名单 4 项"
      - "单测：trackRest 产生等价 span"
    description: "US-05/AC-04 + PII 白名单（质疑者2 R1）。"
  - id: T15
    title: "sdk core：性能指标 + sendBeacon flush + nl.sdk.flush 上报"
    deps: [T13]
    acceptance:
      - "nl.first_screen_load 每页仅一个 data point（LCP，兜底 load 事件）"
      - "pagehide / visibilitychange=hidden 触发 forceFlush（OTLP/HTTP sendBeacon）"
      - "flush 失败/超时尽力上报 nl.sdk.flush data point（attributes flush=beacon、result=ok|fail|timeout、dropped_spans=N）；二次失败仅 console，不抛错"
      - "export batch timeout 可配置（默认 2000ms）"
    test_cases:
      - "单测：first_screen_load 仅一个 data point（PerformanceObserver mock）"
      - "单测：visibilitychange=hidden → forceFlush 被调用"
      - "单测：forceFlush reject → nl.sdk.flush{result:'fail', dropped_spans:N} 上报"
    description: "US-06/AC-05 + 质疑者1 R1 关页丢失对策、质疑者3 R2 上报形态固化。"
  - id: T16
    title: "sdk adapters：vue2/vue3 指令 v-biz / v-span + 单例守卫"
    deps: [T13]
    acceptance:
      - "v-biz：组件 mounted 起业务 root span（startBusiness），unmounted 结束（未显式 end 则 end_reason=unmount）；vue2（Vue.directive）与 vue3（app.directive）行为一致"
      - "v-span：mounted 起子 span（parent=当前 ACTIVE 业务），unmounted 结束"
      - "单例守卫：window.__NL_OBSERVE__ 存在时重复 init 返回既有实例并 warn（防基座/子应用双初始化）"
    test_cases:
      - "vue2-host 单测：v-biz 组件挂载/卸载 → 业务 span 起/止断言"
      - "vue3-host 单测：同上（@vue/test-utils v2）"
      - "vue2/vue3 单测：v-span 子 span parent 断言"
      - "双宿主单测：二次 init 返回同一实例"
    description: "US-03/AC-02 + 多应用隔离（风险 #5）。"
  - id: T17
    title: "sdk：OTLP/HTTP 导出接线 + init 配置 + 降级策略"
    deps: [T14, T15, T16]
    acceptance:
      - "init({serviceName, endpoint, exportTimeoutMs?, restPathFilter?}) 接通 WebTracerProvider + OTLP/HTTP traces/metrics exporter，业务 root span 以 ROOT_CONTEXT 为父（保证不同 traceId）"
      - "endpoint 不可达时静默降级（console.debug，不抛错、不阻断业务），span 本地生成不丢失业务办理"
      - "vue2.vue3 双宿主端到冒烟：init→签入→v-biz→fetch→end → exporter 收到完整 trace"
    test_cases:
      - "单测：exporter mock 不可达 → init/业务办理流程无异常抛出"
      - "vue3-host 集成冒烟：InMemory exporter 收到 root span + REST span + 指标 data point"
    description: "US-01/AC-01 闭环；NFR-03。"
  - id: T18
    title: "web-ui 骨架（Vue3 + Vite + TS + API 封装）"
    deps: []
    acceptance:
      - "Vite+TS 工程可构建；路由三页骨架（业务列表/流程/指标）"
      - "observeApi.ts 封装 /api/observe/*，统一解包 {code,message,data}，非 0 code 抛业务错误"
      - "401 时跳转登录（外部网关依赖，配置项 LOGIN_URL）"
    test_cases:
      - "单测：observeApi 解包成功/失败用例（vitest+msw mock）"
    description: "US-10 工程基础。鉴权为外部边界依赖（待澄清 #6）。"
  - id: T19
    title: "web-ui：业务列表 + 业务流程时间线（span 树 + REST span 明细）"
    deps: [T10, T18]
    acceptance:
      - "BusinessesPage：客户编号+时间范围+bizCode 过滤查询业务列表，字段与 API 一致"
      - "TracePage：span 树渲染为时间线/瀑布，业务 root span 与 REST span 层级正确，REST span 展开白名单属性，点击 span 打开详情抽屉"
      - "挂起/恢复 span events 在时间线可见（biz.suspend_start/biz.resume 标记）"
    test_cases:
      - "组件单测（msw mock）：列表渲染、span 树嵌套结构断言、REST span 属性展示、事件标记"
      - "E2E：TracePage 加载样例 trace → 时间线包含业务 span 与 REST span"
    description: "US-04/US-05/AC-09（流程可视）。"
  - id: T20
    title: "web-ui：性能指标卡片（首屏/业务耗时 P95/成功率）"
    deps: [T11, T19]
    acceptance:
      - "MetricsPage：调用 /metrics/summary（恒带 customerId），展示 firstScreenLoadP95 与 perBiz{effectiveDurationP95, successRate, count} 卡片"
      - "空数据展示占位态；错误态展示重试"
    test_cases:
      - "组件单测：mock 数据渲染断言；空数据/错误态断言"
      - "E2E：MetricsPage 加载 → 指标卡片存在性（不断言精确值）"
    description: "US-06/AC-09（指标可视）。"
  - id: T21
    title: "POC：micro-app 沙箱下子应用 span 捕获率验证 + 兜底确认"
    deps: [T14]
    acceptance:
      - "micro-app jsSandbox 开启场景：子应用内 10 次 fetch 请求 ≥9 次自动产生 REST span（捕获率断言）"
      - "不达标时：记录结论并验证 trackRest/v-span 手动兜底路径可完整覆盖（兜底 span parent 正确）"
    test_cases:
      - "poc 集成脚本：vue3 子应用宿主 + mock 后端 → 捕获率断言"
      - "单测：兜底路径 span 结构断言"
    description: "风险 #5（质疑者1 R1）；决定子应用接入文档口径（自动 vs 手动兜底）。"
  - id: T22
    title: "API 测试集合（Newman：query 三接口 + metrics + receiver /config/producer）"
    deps: [T06, T11]
    acceptance:
      - "tests/api/observe-query.collection.json：businesses/trace/span/metrics-summary 正常、参数错误（缺 from/to、跨度>31 天）、404、429 用例齐全，断言 {code,message,data} 结构"
      - "tests/api/observe-receiver.collection.json：/config/producer 参数与需求 yaml 逐项断言"
      - "Newman run 在内网测试环境全绿（不可达时 env-gated 说明）"
    test_cases:
      - "tests/api/observe-query.collection.json（≥12 用例）"
      - "tests/api/observe-receiver.collection.json（参数断言用例）"
    description: "AC-06/AC-08 的接口级回归资产。"
  - id: T23
    title: "E2E 测试（Playwright：全链路办理流程 + 指标存在性，env-gated）"
    deps: [T17, T20]
    acceptance:
      - "full-chain.spec.ts：vue3 宿主页面 → 签入客户 → 业务 A（真实 fetch 至 mock 后端）→ 切业务 B（A 挂起）→ 恢复并结束 A → 结束 B → 经查询 API 断言：2 个业务、traceId 不同、A 的 REST span 归属 A、effectiveDurationMs 在宽松区间"
      - "metrics.spec.ts：first_screen_load data point 存在性"
      - "E2E_BACKEND_URL 未设置时全部 skip（env-gated）；内网可达时全绿"
    test_cases:
      - "tests/e2e/full-chain.spec.ts"
      - "tests/e2e/metrics.spec.ts"
    description: "AC-01…AC-10 的端到端验收；内网可达性未知故 env-gated（待澄清 #7）。"
---

# 任务列表：前端透明化建设

共 **23 个任务**。依据：`docs/designs/2026-09-28-前端透明化建设-design.md`（v3 定稿）与 `docs/plans/前端透明化建设/详细设计.md`。
编码遵循 `nl-sdlc:coding`（TDD 先测后码 + 质量门），测试遵循 `nl-sdlc:testing`。

## 执行顺序（依赖分层）

1. **第 0 层（并行起点）**：T01（POC）、T02（common）、T18（web-ui 骨架）
2. **第 1 层（后端基础）**：T03（DDL，依赖 T02）；T04（依赖 T01+T02）；T12（依赖 T01）
3. **第 2 层（后端服务）**：T05→T06（receiver 链）；T07→T08（storage 链，依赖 T03）；T09（依赖 T02+T03）
4. **第 3 层（查询 API）**：T10→T11
5. **第 4 层（SDK）**：T13→{T14, T15, T16}→T17；T21（依赖 T14）
6. **第 5 层（可视化）**：T19（依赖 T10+T18）→T20（依赖 T11+T19）
7. **第 6 层（测试资产）**：T22（依赖 T06+T11）、T23（依赖 T17+T20）

关键路径：T01 → T04 → T05 → T06 → T22；T02 → T03 → T07 → T08；T02 → T09 → T10 → T11 → T20 → T23。

## 里程碑

| 里程碑 | 内容 | 任务 |
|---|---|---|
| M1 链路打通 | OTLP → Kafka → Databend 全链路写入成功 | T01-T08 |
| M2 可查询 | 查询 REST API 全量可用且通过 Newman 回归 | T09-T11, T22 |
| M3 SDK 可用 | 双版本 SDK 完成，vue2/vue3 宿主冒烟通过 | T12-T17, T21 |
| M4 可视化 | 三页可视化完成，全链路 E2E 通过 | T18-T20, T23 |

## 注意事项

- 所有 Databend/Kafka 凭证走环境变量，代码与文档不落口令（NFR-04）
- DDL 单一事实来源：`backend-observe-common/ddl/DdlScripts`，`scripts/databend/001-init.sql` 与其同步（T03）
- 待澄清问题（SDK 发布形态/采样率/网关落地/E2E 环境可达性等）按设计文档 §2 假设推进，澄清后回写设计
- 任务间引用需求编号：US-01…US-10 / AC-01…AC-10 / NFR-01…NFR-04（见概要设计 §2）

# T01 POC 结论记录（poc-notes.md）

- 任务：POC：collector-embedded 1.2.1 与 OTel 前端依赖验证（供 T04/T12 选型）
- 日期：2026-10-14；环境：Ubuntu 22.04（无 sudo）、Node v26.11.1、npm 11.20.0、Docker 29.9.0（daemon 可用但无镜像）
- 验证方式：可运行测试（JS 侧）+ 网络/仓库探测（Java 侧）+ Maven Central 元数据核对

## 1. collector-embedded 1.2.1（风险 #1）

**坐标存在性：确认存在。**
- 需求坐标 `io.mishmash.opentelemetry:collector-embedded:1.2.1` 在 Maven Central 仓库目录可列出：
  版本序列 1.0.0、1.1.0、1.1.1、1.1.2、1.1.3、1.1.4、1.1.5、1.1.6、1.1.7、1.1.8、1.2.1。
- central.sonatype.com 页面确认描述：*"OpenTelemetry logs, metrics, traces and profiles collectors
  that can be embedded in other java systems as a data source."*
- 注意：Maven Central **搜索 API（search.maven.org/solrsearch）对该 groupId 返回 numFound=0**
  （索引缺失）。若内网私服按搜索索引同步/代理，可能同步不到该坐标——内网接入时需用
  仓库路径直连（`io/mishmash/opentelemetry/collector-embedded/1.2.1/`）验证，不能只依赖搜索。
- 同组其他模块（parent 聚合）：`persistence-protobuf`、`server-parquet`、`druid-otlp-format`
  （1.2.1 同版本）——T04 若需 OTLP/protobuf 持久化格式可评估 `persistence-protobuf`。

**版本矩阵（经 POM 元数据确认，风险 #1 兼容结论）：**
- `collector-embedded:1.2.1` 的 parent 为 `io.mishmash.opentelemetry:opentelemetry-server-embedded:1.2.1`，
  其 `maven.compiler source/target = 17` → **JDK17 编译目标，与 Spring Boot 3/JDK17 矩阵兼容**。
- 传递依赖（由 parent dependencyManagement 锁定）：vertx 5.1.8（vertx-web、vertx-grpc-server）、
  protobuf-java/-util 4.36.1、io.opentelemetry:opentelemetry-api 1.65.0、
  io.opentelemetry.proto:opentelemetry-proto 1.11.0-alpha、netty-bom 4.2.18.Final。
- 注意：receiver 基于 **Vert.x 5** 提供 OTLP 端点（非 servlet），与 Spring Boot MVC 共存无冲突
  （独立端口监听）；protobuf 4.36.1 较新，T04 需确认与 spring-kafka/kafka-clients 无类冲突。

**进程内验证：2026-10-15 经内网私服通路补齐（原网络阻断见 §3）。**
- 私服 `http://10.1.4.161:8085/repository/public/`（`~/.m2/settings.xml` mirrorOf=*）下
  `collector-embedded:1.2.1` 解析成功；`poc/receiver-otlp-kafka` 全模块 `mvn test`
  BUILD SUCCESS（编译错误 `SEND_TIMEOUT_MS_CONFIG`、kafka-clients 作用域、IT 的
  double/long 收窄均已修复并实跑验证）。Spring Boot parent 3.3.5 已过 OSS 补丁支持期
  （评审 A06），T04 开工先升 parent 到受支持线。
- API 面探针已执行：`CollectorEmbeddedProbeTest` 从私服 jar dump 出
  `target/collector-embedded-api.txt`（32 个类，全部位于 `io.mishmash.opentelemetry.server.collector`）。

**导出失败可观测钩子（验收 #2）结论：**
- Plan A（collector-embedded）钩子结论（探针已执行，2026-10-15；完整签名见
  `poc/receiver-otlp-kafka/target/collector-embedded-api.txt`，32 个类均在
  `io.mishmash.opentelemetry.server.collector`）：**1.2.1 jar 只含 collector 核心，
  无内建 exporter，因而不存在「导出失败」回调**。其架构为 OTLP/HTTP+gRPC 接收
  （Vert.x，`AbstractCollector.bind(Router/GrpcServer)`）→ 扁平化（`TracesFlattener` 等）
  → 逐条分发给 `SpansSubscriber.onNext(Span)` / `MetricsSubscriber` / `LogsSubscriber`；
  `Span.getSpan()` 暴露原始 `io.opentelemetry.proto.trace.v1.Span` protobuf 对象，
  自带 `Instrumentation`（micrometer）自监控。即 Plan A 下 Kafka 写入是**实现方在
  subscriber 里自写的代码**，AC-10 的丢弃计数点仍是 Plan B 的 producer 异常计数
  （同步+异步两路径）——无论选哪条接收路径，钩子点相同。
  T04 选型取舍：Plan A 得原生 OTLP/HTTP+gRPC 解析（protobuf+JSON）与逐条分发，
  但需处理 Vert.x 独立端口并自写 `Span`→topic 载荷映射；Plan B 全在 Spring MVC 栈内。
  （探针自身曾有两处缺陷已修：`cl.getResources("")` 扫不到 Maven 布局依赖 jar；
  评审 Major。）
- Plan B（已实现；**评审修正**）：`KafkaTemplate.send(...).whenComplete(...)` 只覆盖**入队成功后**
  的异步失败；broker 不可达时 `send()` 在 max-block.ms(5000) 内取不到 metadata 会**同步抛出**
  `TimeoutException`（kafka-clients javadoc），旧实现会让异常穿透为 HTTP 500 且计数不递增，
  与 AC-10 语义矛盾。现已在 controller 内 try/catch：同步异常分支 `dropped.increment()` 后仍返回
  200，异步失败仍由 whenComplete 计数。`ExportFailureHookIT` 断言 200 + 计数递增——但该 IT
  属 failsafe（`*IT`），**重跑命令为 `mvn verify`**（旧 `mvn test` 不会执行它，评审 Major 已修）。
- 结论：collector-embedded 无丢弃回调（其内根本没有 exporter），AC-10 计数点即 Plan B 的
  producer 异常计数（同步+异步两路径）；T04/T05 按详设 §1.2 既定策略执行。
  **状态（2026-10-15 私服打通后）**：验收 #2 已由 `ExportFailureHookIT` 实跑验证通过（见 §4.2）；
  验收 #1（`ReceiverOtlpKafkaIT`，OTLP→真实 Kafka topic 断言）仍需内网镜像仓库环境关闭（§4.3）。

## 2. OTel 前端 SDK（风险 #7，验收 #3）

**关键发现：npm 上不存在 `@opentelemetry/sdk-web`（registry 返回 404）。**
替代选型（已在双宿主实测通过）：

| 包 | 锁定版本 | 说明 |
|---|---|---|
| @opentelemetry/api | 1.9.1 | |
| @opentelemetry/sdk-trace-web | 2.12.0 | WebTracerProvider（替代 sdk-web） |
| @opentelemetry/sdk-trace-base | 2.12.0 | SimpleSpanProcessor/InMemorySpanExporter |
| @opentelemetry/sdk-metrics | 2.12.0 | T15 指标用（POC 仅装依赖） |
| @opentelemetry/resources | 2.12.0 | resourceFromAttributes |
| @opentelemetry/exporter-trace-otlp-http | 0.223.0 | OTLP/HTTP exporter（与 core 2.12.0 配套） |
| vue | 3.5.44 / 2.7.16 | vue3 宿主 / vue2 宿主（2.x 末版） |
| @vue/test-utils | 2.5.1 / 1.3.6 | 对应双宿主 |
| vitest + @vitest/coverage-v8 | 5.0.4 | jsdom 26.1.0，TS 5.9.3。3.2.7 因传递依赖 tinypool≤2.1.1 两枚 critical 公告（GHSA-5gmw-xhrv-c9v3 / GHSA-85c8-ppgw-ccpr）无法过 `npm audit --audit-level=high` 门，升级至 5.0.4（Node ≥22.12，本环境 v26 满足）后双宿主 8 用例全绿 |

**OTel JS 2.x API 变化（T12/T13 实现必须按此写）：**
1. `new Resource({...})` 构造器已移除 → `resourceFromAttributes({...})`。
2. `provider.addSpanProcessor(...)` 已移除 → 构造器 `spanProcessors: [...]` 传入。
3. `ReadableSpan.parentSpanId` → `parentSpanContext`（断言/导出转换都要改）。
4. 全局注册一次性：`provider.register()` 后全局 tracer 不可替换（重复注册被忽略并告警）。
   → 印证详设 §5.1 `window.__NL_OBSERVE__` 单例守卫的必要性；POC 用 `provider.getTracer`
   显式取 tracer，正式 SDK 全局注册仅一次。

**双宿主测试结果（TDD，先失败后通过）：**
- `poc/sdk-vue3-init`：4/4 通过（root span 导出、双业务 traceId 不同、组件内 REST 子 span
  parent 归属、OTLPTraceExporter 可构造）；覆盖率 100%（coverage/ 已生成）。
- `poc/sdk-vue2-init`：4/4 通过（同上，vue@2.7.16 + @vue/test-utils@1.3.6）；覆盖率 100%。
- 首轮失败记录（TDD 证据）：`Resource is not a constructor` → `addSpanProcessor is not a
  function` → 全局注册一次性导致跨用例串扰 → `parentSpanId` undefined，逐一修复后全绿。

## 3. 内网 Maven/npm 仓库可得性（验收 #4）

**npm：可得。** registry.npmjs.org 直连 200，上表全部锁定版本均存在并成功安装。

**更新 2026-10-15：内网私服通路已打通（用户指定）**——
`http://10.1.4.161:8085/repository/public/`（Nexus public 组，`~/.m2/settings.xml` mirrorOf=*）：
collector-embedded 1.2.1、testcontainers、failsafe/jacoco 等依赖与插件均 200 下载成功。
容器镜像问题仍未解决：Nexus 无 docker 代理仓库、Docker Hub 通路仍阻断 →
Testcontainers Kafka 镜像依旧不可用，`ReceiverOtlpKafkaIT`（验收 #1）仍受阻。
以下为原排查记录（保留作内网接入核对清单）：

**Maven：本环境不可得（已穷举）：**
- repo.maven.apache.org / repo1.maven.org：TCP 可达但 TLS 握手超时（SNI 级阻断）；HTTP:80 仅 301。
- 镜像探测全部失败：maven.aliyun.com、repo.huaweicloud.com、mirrors.cloud.tencent.com、
  maven-central.storage-download.googleapis.com、mirrors.ustc.edu.cn（000）；
  mirrors.sjtug.sjtu.edu.cn/maven-central 仅 302 回 Central；TUNA 无 maven-central 镜像（404）。
- 容器镜像（Testcontainers 前置）：registry-1.docker.io、quay.io、ghcr.io、mcr.microsoft.com
  均 000；本地 docker images 为空 → Testcontainers Kafka 无法在本环境启动。
- 工具链本身可得（无 sudo 方案已验证）：`apt-get download openjdk-17-jdk-headless
  openjdk-17-jre-headless` + `dpkg -x` 解包（需修复解包树内指向 /etc 的断链符号链接）→
  JDK 17.0.20.1 可用；Maven 3.9.16 从 mirrors.tuna.tsinghua.edu.cn/apache 下载可用。
- **替代版本结论**：坐标本身无需替代（1.2.1 存在）；需要的是**仓库通路**——T04 开工前必须
  打通内网 Maven 私服到 Central 的代理（或人工导入该坐标到私服），并把 Kafka 镜像
  （confluentinc/cp-kafka:7.7.1 或 apache/kafka）预拉到内网镜像仓库。

## 4. 重跑台账（2026-10-15，私服通路打通后）

1. **已完成**：`mvn test` BUILD SUCCESS；探针 dump 32 类 → §1 Plan A 钩子结论已回填
   （无 exporter 即无导出失败回调；Kafka 写入为 subscriber 自写代码，钩子点即 Plan B 的
   producer 异常计数）。
2. **已完成（验收 #2）**：`mvn verify -Dit.test=ExportFailureHookIT` 实跑通过——
   broker 不可达（127.0.0.1:19092）时 OTLP 入口仍 200 且 `nl.receiver.dropped` 递增，
   AC-10 语义在真实 JVM 中验证（该 IT 无需 Docker）。首跑暴露并修复两个**潜伏缺陷**
   （Java 侧此前从未运行过，属评审 Critical「未验证」的真实代价）：
   - `@Bean Map<String,Object> kafkaProducerProps` 的 `Map<String,Object>` 注入点被
     Spring 解析为「收集全部 bean」→ `producerFactory→controller→kafkaTemplate` 循环依赖，
     ApplicationContext 启动失败；已改为非 @Bean 方法、`producerFactory` 直接收
     `KafkaExporterProperties`；
   - 仅 micrometer-core（无 actuator）不提供自动装配 `MeterRegistry`；新增
     `ObservabilityConfig` 显式注册 `SimpleMeterRegistry`。
   另修复：kafka-clients 作用域（test→compile）、`ExportFailureHookIT` double/long 收窄、
   `SEND_TIMEOUT_MS_CONFIG`（评审 Blocker，见旧记录）。
3. **仍受阻（验收 #1）**：`ReceiverOtlpKafkaIT` 需 Testcontainers Kafka 镜像——Nexus 无
   docker 代理、Docker Hub 阻断。需在预拉镜像的内网环境全量 `mvn verify` 关闭。
4. **仍受阻（门）**：`mvn dependency-check:check` 的 NVD 源不可达——解读门结果须区分
   「网络不可达」与「漏洞失败」（见 §6）。

## 5. 对 T04/T12 的选型建议

- T04（receiver 嵌入）：pom 坐标照需求用 `io.mishmash.opentelemetry:collector-embedded:1.2.1`；
  但**必须**先在内网跑通探针确认 API；失败钩子按 Plan B 兜底（同步+异步两路径，见 §1）。
  POC 包名已更正为 `com.nl.observe.*`；**生产模块（T04+）按详细设计用 `com.nlolobserve.*`**
  （详细设计 §包结构为契约；`nlolobserve` 疑似 `nl-observe` 拼写错误，统一改名须先改设计文档）。
  T04 开工前置安全项（安全评审登记）：入口鉴权/限流/body 上限（T06 范围）、
  `nl.customer_id` 白名单格式校验（不得信任浏览器自报租户）、Kafka SASL/SSL 配置项、
  Testcontainers 镜像按 digest 固定。
- T05：producer 参数已在 `KafkaProducerConfig` 与需求 yaml 逐项一致（acks=all、idempotence、
  lz4、linger 5、batch 65536、delivery 20s、request 15s、retries MAX、max-in-flight 5、
  max-block 5s、buffer 64MB、send-timeout 9s），可直接复用。
- T12（sdk-web 骨架）：OTel 依赖版本按 §2 表锁定；代码按 §2「2.x API 变化」四条编写；
  双宿主工程结构可直接沿用 poc/sdk-vue2-init、poc/sdk-vue3-init（vitest+jsdom+test-utils 组合已验证）。

## 6. 质量门口径（仓库根聚合，2026-10-15 修复轮）

- 首轮质量门 `{"overall":"fail"}` 根因：`scripts/quality-gate.sh --scope all` 在仓库根执行，
  而根目录无 pom.xml/package.json（project_type=none，门脚本只识别当前目录）。
- 修复：仓库根升级为 npm workspaces 聚合工程（根 package.json / tsconfig.json /
  eslint.config.mjs / vitest.config.ts / .gitignore），workspaces 覆盖 poc/sdk-vue2-init
  与 poc/sdk-vue3-init；门四项（eslint、vue-tsc、覆盖率、npm audit）均在根执行，
  覆盖率产物为根 coverage/coverage-summary.json（双宿主聚合，lines 100%）。
  门脚本落位 scripts/quality-gate.sh（复制自 nl-sdlc 插件），保证根目录命令可用。
- 依赖锁定改由根 package-lock.json 单一事实来源（双宿主嵌套 lockfile 已删除，
  npm workspaces 安装时忽略嵌套 lock）。
- Java 侧（poc/receiver-otlp-kafka）不在根放 pom.xml，门本轮只跑前端侧：本环境
  Maven Central 不可达（§3），checkstyle/spotbugs/jacoco/dependency-check 无法执行；
  §4 未竟项继续有效，内网通路打通后在 poc 目录跑 `mvn test jacoco:report` 并评估
  根聚合 pom 纳入后端门。
- vue@2.7.16 存在 moderate ReDoS 公告（GHSA-5j4c-8p2g-v4jx，2.x 已 EOL 无修复版本），
  低于 high 不阻断门；vue2 宿主仅 POC 验证用，正式 SDK 以 peerDependency 由宿主自带 vue。
- **门状态口径更正（评审 Minor）**：`npm audit` 项依赖 registry 可达性——评审环境复跑
  `--scope frontend` 得 `overall:fail`（eslint/vue-tsc/coverage 均 pass，仅 npm-audit 因
  endpoint 不可达而 fail）。门脚本把网络失败与漏洞失败混为一谈；解读门结果时须区分
  「漏洞失败」与「网络不可达」。本仓库开发期以本地已安装树 + lockfile 为准。
- 根 package-lock.json 曾由手工合并产生（84/349 条目缺 `resolved`+`integrity`，安全评审
  A08 Medium），已删除并用 `npm install` 重新生成为 npm 原生产物。

## 7. T21 POC：micro-app 沙箱下子应用 span 捕获率验证（2026-10-17）

工程：`poc/micro-app-capture/`（workspace 成员；独立跑 `npm test -w poc-micro-app-capture`，
根 vitest projects 亦聚合，project 名 `poc-micro-app-capture`）。
场景矩阵：版本线 × jsSandbox 开/关 × SDK 放置（sub=子应用内自初始化 / host=宿主初始化、
子应用经包装 fetch 自动捕获）× 10 次 fetch → 自动捕获断言（验收 #1：≥9/10）+ trackRest
兜底补记断言（验收 #2：完整覆盖且 parent 正确）。

### 实测捕获率（jsdom 26.1.0 + vitest 5.0.4，0.8.11 稳定线）

```
[T21-POC] line=0.8.11 sandbox=on  sdk=sub  auto=10/10 fallback=10/10 fetchErrors=0
[T21-POC] line=0.8.11 sandbox=on  sdk=host auto=10/10 fallback=10/10 fetchErrors=0
[T21-POC] line=0.8.11 sandbox=off sdk=sub  auto=10/10 fallback=10/10 fetchErrors=0
[T21-POC] line=0.8.11 sandbox=off sdk=host auto=10/10 fallback=10/10 fetchErrors=0
```

**验收 #1 达成**：0.8.11 jsSandbox 开启下自动 REST span 10/10 ≥ 9/10（两种 SDK 放置均满足），
无需降级到兜底路径。**验收 #2 同步验证**：兜底 trackRest 补记在全部场景 10/10 完整、
parent 均钉在业务 root（span 名 `HTTP {METHOD}`、kind=CLIENT、白名单 4 属性、route 去 query），
v-span 手动钉 parent 由 fallback.spec.ts 单测覆盖。

### 结论与裁决

- 0.8.11 稳定线（集成文档推荐线）沙箱内 fetch 穿透 with-scope → proxy get 回退 rawWindow →
  宿主包装的 fetch 可见 → 自动埋点足够；沙箱对 SDK 自动 REST 捕获无实质损耗。
- 兜底路径（trackRest/v-span）是框架无关的 SDK 能力，已验证可用、parent 语义正确，
  作为沙箱保真度不确定场景（rc 线、iframe、shadowDOM 组合）的保险，非默认必需。
- rc.32（1.0.0-rc.32）**沙箱线**在 jsdom 下不可跑通（见下），其捕获率留真实浏览器复验
  （跟进项）；**无沙箱线**在 jsdom 下可跑通（探针实测 MOUNTED 达、就绪信号达）。

### rc.32 线 jsdom 保真缺口（capture-rc32.spec.ts 探针钉住；2026-10-17 评审后更正）

- **前提（评审 arch M-1 修复）**：rc.32 的 `customElements.define('micro-app')` 只发生在
  `start()` 内（lib 唯一调用点 defineElement）。探针必须先 `startRcOnce()` 再 `renderApp`
  ——旧版探针未调 start()，元素未定义、挂载路径根本不执行，「就绪不达」等结论系空转假象。
  修复后探针钉住真实机制（下述均为 start() 后的实测）。
- sandbox 开：挂载收尾 `sandBox.microAppWindow.dispatchEvent(CustomEvent)`（CustomWindow 是
  普通对象作 proxy target）→ jsdom EventTarget 品牌校验拒绝（TypeError: "not a valid
  instance of EventTarget"）→ MOUNTED 不达 → renderApp 预算内不 resolve。品牌校验
  unhandled rejection 由**探针自捕获**（beforeAll 换装 process 级监听器 + 文案断言，
  afterAll 还原）——不设项目级 `dangerouslyIgnoreUnhandledErrors`（评审 sec M-1/arch
  m-1/corr Min-1：该 flag 在 vitest 5 是 NonProjectOptions，写在被目录引用的工程配置
  顶层作用域不确定，可能吞掉根聚合全部未处理异常，属测试完整性后门；去 flag 后根聚合
  实跑 93/93 绿，结论留痕于此）。
- 沙箱挂载崩溃后 `unmountApp` 挂起（预算内不 resolve）——探针以 race 超时预算处理，不裸 await。
- sandbox 关：不触发 CustomWindow.dispatchEvent → 品牌校验不参与 → renderApp resolve(true)
  （MOUNTED 达）+ 子应用就绪信号达（即便前序沙箱探针崩溃后进程内状态残留亦不影响）。
  旧「完成性依赖先前状态/就绪不达」说法系未 start 假象，已更正。
- 真实浏览器该路径预期成立（品牌校验是 jsdom 特有约束）；跟进项：真浏览器（Playwright）
  跑同工装复验 rc.32 **沙箱线**捕获率。
- 跟进项（评审 sec M-2）：host 模式兜底桥接（`poc:track-rest` 事件透传宿主 client.trackRest）
  对载荷零校验——正式集成落地时该信道必须加事件来源鉴别（isTrusted/目标元素归属）+
  字段类型与取值范围校验，否则任意能向容器派发 DOM 事件的脚本可伪造 REST span 钉在
  宿主业务 root 上（遥测注入/篡改）。POC 内 bundle 受信故不校验（harness/control 两处
  注释已标注）。

### 双版本线 API 面差异（集成文档需标注）

| 点 | 0.8.11 | 1.0.0-rc.32 |
|---|---|---|
| 挂载 | 声明式 `<micro-app>` 元素 | `renderApp(options)` 命令式（Promise，resolve 于 MOUNTED/ERROR） |
| 卸载 | `unmountApp` 独立命名导出 | 实例方法 `microApp.unmountApp` |
| 沙箱 | 默认开，`disable-sandbox` 属性关 | 同语义经 options `'disable-sandbox': true` |
| start() | 可重复（warn） | 仅一次（hasInit，重复 logError） |

### jsdom 环境 shim（POC 工装弥合，正式集成不需要）

- fetch 绑定：浏览器 globalThis===window；vitest jsdom 下分裂（undici vs 无）→
  `installFetchBindingShim()` 统一两侧 get/set。
- rc.32 模块顶层 `new Proxy(window.Worker,…)`：jsdom 无 Worker → `src/worker-shim.ts`
  经本工程 vitest `setupFiles` 集中补桩（先于一切测试模块求值，不再依赖各 spec
  手工导入顺序，评审 m-4）。
- 沙箱内 SDK init：proxyWindow 非真 EventTarget → `windowLike: null, performanceObserver: null`
  离线模式（不注册窗口监听器）。
- esbuild JS API 在 jsdom worker 内模块加载断言必假 → bundle 构建走子进程
  `app/build-bundle.mjs`；`import.meta.url` 被 jsdom 改写 → 按 cwd 探测工程目录。
- 两阶段确定性握手 `poc:phase1 → poc:phase1-ack → poc:done`：无 ack 则兜底 span 泄漏进
  自动快照；host 模式另以基线 id 集合扣除 micro-app 自身资源 fetch（html+bundle）。

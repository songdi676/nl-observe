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

**进程内启动验证：本环境无法完成（被网络阻断），见 §4。**
- 已产出工程 `poc/receiver-otlp-kafka/`（pom 声明该依赖为 optional + JDK17 + Spring Boot 3.3.5），
  在有 Maven 仓库访问的环境执行 `mvn verify` 即可完成验收 #1（OTLP/HTTP→Kafka topic 断言）。
  **本环境从未编译/运行过 Java 侧**（评审 Critical：验收 #1 未验证即交付的根因是网络阻断，
  非代码缺陷；但旧代码确有编译错误 `SEND_TIMEOUT_MS_CONFIG`，已修，见 §4.4）。
  Spring Boot parent 3.3.5 已过 OSS 补丁支持期（评审 A06），T04 开工先升 parent 到受支持线。
- API 面探针：`CollectorEmbeddedProbeTest` 在 jar 可解析时自动 dump 全部 `io/mishmash/**`
  类的公开方法/构造器签名到 `target/collector-embedded-api.txt`，用于确认启动/停止与
  导出失败钩子（无需预先知道其 API）。

**导出失败可观测钩子（验收 #2）结论：**
- Plan A（collector-embedded）：钩子点待探针输出确认（jar 不可下载，本环境无法枚举其回调 API）。
  探针已修正为解析 `java.class.path` + 兜底扫 `~/.m2` 并用 `URLClassLoader` 反射 dump
  （旧版 `cl.getResources("")` 在 Maven 布局下永远扫不到依赖 jar，评审 Major 已修）。
- Plan B（已实现；**评审修正**）：`KafkaTemplate.send(...).whenComplete(...)` 只覆盖**入队成功后**
  的异步失败；broker 不可达时 `send()` 在 max-block.ms(5000) 内取不到 metadata 会**同步抛出**
  `TimeoutException`（kafka-clients javadoc），旧实现会让异常穿透为 HTTP 500 且计数不递增，
  与 AC-10 语义矛盾。现已在 controller 内 try/catch：同步异常分支 `dropped.increment()` 后仍返回
  200，异步失败仍由 whenComplete 计数。`ExportFailureHookIT` 断言 200 + 计数递增——但该 IT
  属 failsafe（`*IT`），**重跑命令为 `mvn verify`**（旧 `mvn test` 不会执行它，评审 Major 已修）。
- 结论：即使 collector-embedded 无丢弃回调，Plan B 的 producer 异常计数点（同步+异步两路径）
  已覆盖 AC-10 需求；T04/T05 按详设 §1.2 既定策略执行（有回调用回调，无则 micrometer 包装）。
  **注意：以上 Java 侧断言在本环境从未实际运行（Maven Central/Docker 镜像均不可达），
  验收 #1 仍属未验证状态，须在打通仓库的环境按 §4 重跑回填后方可关闭。**

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

## 4. 本环境未竟项（需在打通仓库后重跑）

1. `cd poc/receiver-otlp-kafka && mvn verify` → surefire 跑探针、failsafe 跑验收 #1
   （ReceiverOtlpKafkaIT）与 #2（ExportFailureHookIT），jacoco:report 绑定 verify 阶段
   聚合两者覆盖率。（旧口径 `mvn test jacoco:report` 不会执行 `*IT`，已更正。）
2. `CollectorEmbeddedProbeTest` 输出 `target/collector-embedded-api.txt` → 回填 §1 钩子结论
   （Plan A 是否有导出失败回调）。
3. 若 Plan A 探针显示无失败钩子 → 按 Plan B（已实现）接线，T04 不再依赖 Plan A 钩子。
4. 重跑前确认 `KafkaProducerConfig` 不再引用不存在的 `ProducerConfig.SEND_TIMEOUT_MS_CONFIG`
   （评审 Blocker：kafka-clients 无此常量，旧代码必编译失败；已删除，send-timeout 仅保留为
   需求侧参数供 /config/producer 回显）。

## 5. 对 T04/T12 的选型建议

- T04（receiver 嵌入）：pom 坐标照需求用 `io.mishmash.opentelemetry:collector-embedded:1.2.1`；
  但**必须**先在内网跑通探针确认 API；失败钩子按 Plan B 兜底（同步+异步两路径，见 §1）。
  包名已更正为 `com.nl.observe.*`（旧 `com.nlolobserve` 为拼写错误，评审 Nit 已修，T04 直接沿用）。
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

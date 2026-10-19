# nl-observe 演示栈（本机 Docker 部署）

全链路演示：浏览器 → demo-host → receiver → kafka → storage → Databend → query → web-ui。
**所有对外端口绑定 `0.0.0.0`（局域网可访问）**。

## 拓扑

| 服务 | 镜像 | 宿主端口 | 说明 |
|---|---|---|---|
| demo-host | nginx:alpine | **8083** | 演示宿主页；`/v1` 同源代理 → receiver:4318（绕 CORS 缺口），`/mock-api` 内置 mock 后端 |
| web-ui | nginx:alpine | **8082** | 查询界面；`/api/observe` 同源代理 → query:8081 |
| receiver | temurin-17 + fat-jar | **4318** / **8080** | OTLP/HTTP 入口（4318）+ producer 配置接口（8080）；`OBSERVE_ALLOWED_CIDRS=0.0.0.0/0`（仅演示） |
| query | temurin-17 + fat-jar | **8081** | `/api/observe/**` 三查询接口（Databend RO 账号） |
| databend | datafuselabs/databend | **8010**→8000 | HTTP 查询 API（root 空密码，仅演示） |
| kafka | apache/kafka (KRaft) | 无（容器内） | 三 topic `nl-observe.otlp.{traces,metrics,logs}` 首 produce 自动建 |
| storage | temurin-17 + fat-jar | 无 | kafka → Databend 落库 |
| init | temurin-17 | 无 | 一次性：建 `observe` 库 + 三表（`tools/DatabendDdlInit.java` 单文件源码运行） |

## 启动步骤

```bash
# 0) 构建宿主侧产物（三 fat-jar + web-ui/dist + demo-host/dist）
bash build.sh

# 1) 起基础层：kafka / databend / receiver / web-ui / demo-host
docker compose up -d kafka databend receiver web-ui demo-host

# 2) 起数据层：init 自动先跑（建 observe 库+三表，成功后 storage/query 才启动）
docker compose up -d storage query

# 查看状态 / 日志
docker compose ps
docker compose logs -f query
```

## 演示访问地址（局域网，`<HOST>` 为部署机 IP）

- 演示宿主页：`http://<HOST>:8083` — 按按钮走完整办理流程
- 查询界面：`http://<HOST>:8082`（选客户号 → 查链路/业务/指标）
- query API：`http://<HOST>:8081/api/observe/...`
- OTLP 入口：`http://<HOST>:4318/v1/traces`（任意 OTLP SDK/`curl` 可直发）
- receiver 配置接口：`http://<HOST>:8080/config/producer`
- Databend HTTP API：`http://<HOST>:8010`（`curl -u root http://<HOST>:8010/health`）

宿主页上报周期约 2s（BSP）；点「冲刷」立即强制上报；稍后到 web-ui 选客户号查询即可见。

## 验证（可选）

```bash
# E2E 打真实栈（Playwright 自动带 --disable-web-security，浏览器侧 CORS 缺口不影响）
E2E_OTLP_URL=http://<HOST>:4318 E2E_BACKEND_URL=http://<HOST>:8081 npm run test:e2e
```

## 注意（演示口径，勿用于生产）

- `OBSERVE_ALLOWED_CIDRS=0.0.0.0/0` 对全网开放 OTLP 入口；生产按详设 §5 收紧内网段
- Databend root 空密码、Kafka 无鉴权——仅隔离网络演示
- 重建干净环境：`docker compose down -v`（kafka/databend 数据随卷清除）

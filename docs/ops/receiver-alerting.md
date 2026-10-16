# 接收端（receiver）监控与告警建议（F7 留痕）

T05/T06 落地。本文档记录接收端暴露的 Prometheus 指标与推荐告警规则，作为后续接入
Prometheus/告警平台（T10+ 运维视图）的基线。

## 1. 指标清单（receiver 进程内）

| 指标名（Micrometer 规范名） | Prometheus 名 | 标签 | 语义 | 引入任务 |
|---|---|---|---|---|
| `nl.receiver.dropped` | `nl_receiver_dropped` | — | Kafka 发送快速失败（max-block 5s）后丢弃的消息计数：OTLP 入口对客户端仍返回 200（避免浏览器端重试风暴），数据实际未落 Kafka，靠本计数暴露 | T05 |
| `nl.receiver.rejected` | `nl_receiver_rejected` | `reason=ip\|size\|rate` | 入口安全过滤器主动拒绝计数（T06）：`ip`=IP 白名单外 403（**OTLP 三入口与 MVC /config/producer 均计入**，两侧共用本指标）、`size`=请求体超 max-request-bytes 413、`rate`=超 rate-limit-per-min 429 | T06 |

> **暴露前置条件（现状说明）**：receiver 当前以进程内 `SimpleMeterRegistry` 注册上述
> 计数器（未引入 actuator）。接入 Prometheus 需要后续跟进项：引入
> `micrometer-registry-prometheus` + Spring Boot Actuator 并开放
> `/actuator/prometheus`（该端点须纳入 T06 白名单口径，不得暴露到内网白名单之外），
> 或由监控 agent（如 JMX exporter）采集。在暴露落地前，以下告警规则为**建议基线**，
> 随暴露跟进项一并生效。

## 2. 推荐告警规则（Prometheus rule 片段）

```yaml
groups:
  - name: nl-observe-receiver
    rules:
      # Kafka 不可达/发送失败：入口 200 但数据被丢弃——最需要第一时间知道的静默故障
      - alert: NLObserveReceiverDroppingSpans
        expr: rate(nl_receiver_dropped_total[5m]) > 0
        for: 2m
        labels: { severity: warning }
        annotations:
          summary: "receiver 正在丢弃 OTLP 数据（Kafka 不可达或发送超时）"
          description: "过去 5 分钟 nl.receiver.dropped 增速 {{ $value }}/s；检查 Kafka broker 连通性与 observe.kafka.* 配置。"

      # 白名单外流量持续到达：白名单配置错误或存在未授权访问尝试
      - alert: NLObserveReceiverIpRejected
        expr: rate(nl_receiver_rejected_total{reason="ip"}[5m]) > 1
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "OTLP/MVC 入口持续出现非白名单来源请求"
          description: "核对 observe.security.allowed-cidrs（环境变量 OBSERVE_ALLOWED_CIDRS）与调用方实际出口 IP 是否一致。"

      # 限流触发：调用方频率过高或 rate-limit-per-min 配置过紧
      - alert: NLObserveReceiverRateLimited
        expr: rate(nl_receiver_rejected_total{reason="rate"}[5m]) > 1
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "OTLP 入口持续触发限流（429）"
          description: "核对 observe.security.rate-limit-per-min 与前端上报频率；持续 429 意味着数据未入库。"

      # 超大请求体：客户端异常或 max-request-bytes 配置过紧
      - alert: NLObserveReceiverOversizedPayload
        expr: rate(nl_receiver_rejected_total{reason="size"}[5m]) > 0
        for: 5m
        labels: { severity: info }
        annotations:
          summary: "OTLP 入口出现超过 max-request-bytes 的请求体（413）"
          description: "核对 observe.security.max-request-bytes 与 SDK 端批量大小（flush 合并/压缩前体积）。"
```

## 3. 相关配置（详设 §5）

```yaml
observe:
  security:
    allowed-cidrs: ${OBSERVE_ALLOWED_CIDRS:10.0.0.0/8}   # 直连口径 CIDR 白名单，不信任 X-Forwarded-For
    max-request-bytes: 10485760                           # 请求体上限（10MB），超限 413/41301
    rate-limit-per-min: 600                               # 每客户端 IP 每分钟上限（滑动窗口），超限 429/42901
```

## 4. 部署核对清单（T06 评审 F8 留痕）

- 默认 `OBSERVE_ALLOWED_CIDRS` 为 `10.0.0.0/8`（偏宽）：生产部署**必须**按实际出口收紧，
  未收紧即等于内网全域可注入遥测（威胁模型见 §5 备注）。
- receiver 启动日志会打印生效安全配置（`白名单=… rate-limit-per-min=… max-request-bytes=…`）
  与 OTLP 端口——部署/变更后核对启动日志即知实际生效值。
- `allowed-cidrs` 配错（非法 CIDR/空白名单）在**应用构造期 fail-fast** 拒启动，不存在
  "静默放行一切"或"静默拒绝一切后无人知晓"的中间态（空白名单语义=拒绝一切，fail-closed）。

### 备注：应用层认证缺位（既定设计，评审 F7）

OTLP 摄取当前无应用层认证，控制面仅为网络层 CIDR 白名单：白名单内（默认 10/8 全域）
任意主机可注入伪造 telemetry（span/metric/log 投毒），`rejected` 指标对此无感。
这是 T06 既定设计（质疑者2 R1：信任边界=网关/网络层单点）；后续 token/mTLS 鉴权
为跟进项，威胁模型与本文档一并评审。

## 5. 运维判读

- `dropped` 上涨而入口仍 200：**静默数据丢失**——优先查 Kafka（broker 存活、topic 存在、
  网络/凭证），入口侧无需处理（AC-10 设计行为）。
- `rejected{reason=ip}` 上涨：先怀疑**白名单误配**（调用方出口 IP 不在 CIDR 内），再考虑
  未授权访问；调整 `OBSERVE_ALLOWED_CIDRS` 需重启 receiver 生效。
- `rejected{reason=rate}` 与 `rejected{reason=size}` 上涨：对照前端 SDK 的上报策略
  （batch/合并/压缩）与 receiver 阈值，二者取其一放宽，避免误伤正常流量。

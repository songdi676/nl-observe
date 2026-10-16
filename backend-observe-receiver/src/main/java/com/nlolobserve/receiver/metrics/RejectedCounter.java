package com.nlolobserve.receiver.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 安全拒绝计数指标 nl.receiver.rejected（T06，详设 §4.2「非法/超大/超频请求直接拒绝」的可观测面）。
 * 与 nl.receiver.dropped（Kafka 发送丢弃）分置：rejected 记录入口过滤器主动拒绝，
 * 是白名单误配/攻击/超频的运维信号（Prometheus 告警接入建议见 docs/ops/receiver-alerting.md）。
 * reason 标签：ip=白名单拒绝(403)、size=请求体超限(413)、rate=限流(429)。
 */
@Component
public class RejectedCounter {

    /** 指标名（与 nl.receiver.dropped 同族命名） */
    public static final String METRIC_NAME = "nl.receiver.rejected";

    /** 拒绝原因标签：IP 白名单 */
    public static final String REASON_IP = "ip";
    /** 拒绝原因标签：请求体超限 */
    public static final String REASON_SIZE = "size";
    /** 拒绝原因标签：限流 */
    public static final String REASON_RATE = "rate";

    private final MeterRegistry registry;

    public RejectedCounter(MeterRegistry registry) {
        this.registry = registry;
    }

    public void increment(String reason) {
        registry.counter(METRIC_NAME, "reason", reason).increment();
    }

    public double count(String reason) {
        io.micrometer.core.instrument.Counter counter =
                registry.find(METRIC_NAME).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }
}

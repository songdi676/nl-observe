package com.nlolobserve.receiver.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 丢弃计数指标 nl.receiver.dropped（详设 §1.2 metrics/，AC-10）。
 * collector-embedded 无内建 exporter、无导出失败回调（T01 poc-notes §1），
 * 计数点在 Kafka 写入订阅者（KafkaSignalSubscriber，send 在专用后台执行器上执行）：
 * 入队后异步失败（whenComplete）、发送积压队列满拒绝、send 同步抛出三路径均 increment。
 * 运维可见性（安全评审 F7，本任务仅留痕）：dropped 增长应接入告警/仪表盘
 * （如 Prometheus 采集后 rate(nl_receiver_dropped[5m]) > 0 告警），随 T06 可观测性收尾。
 */
@Component
public class DroppedCounter {

    /** 指标名（详设 §1.2/§4.2 固定契约，Newman/监控按此断言） */
    public static final String METRIC_NAME = "nl.receiver.dropped";

    private final Counter counter;

    public DroppedCounter(MeterRegistry registry) {
        this.counter = registry.counter(METRIC_NAME);
    }

    public void increment() {
        counter.increment();
    }

    public double count() {
        return counter.count();
    }
}

package com.nlolobserve.receiver.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 丢弃计数指标 nl.receiver.dropped（详设 §1.2 metrics/，AC-10）。
 * collector-embedded 无内建 exporter、无导出失败回调（T01 poc-notes §1），
 * 计数点在 Kafka 写入订阅者：send() 同步异常（max-block 内取不到 metadata）
 * 与入队后异步失败（whenComplete）两路径均 increment。
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

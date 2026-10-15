package com.nlolobserve.receiver.collector;

import java.util.concurrent.Flow;

import com.nlolobserve.receiver.config.KafkaExporterProperties;
import com.nlolobserve.receiver.metrics.DroppedCounter;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 写入订阅者公共骨架（T05）。
 * 丢弃计数两路径（T01 poc-notes §1 Plan B 结论）：
 * 1) broker 不可达时 KafkaTemplate.send() 在 max-block-ms(5s) 内取不到 metadata 会**同步抛出**
 *    TimeoutException —— 必须捕获计数且入口仍返回 200（AC-10，避免浏览器端重试风暴）；
 * 2) 入队成功后的异步失败由 whenComplete 异常分支计数。
 */
abstract class KafkaSignalSubscriber {

    protected final KafkaTemplate<String, String> kafka;
    protected final KafkaExporterProperties props;
    protected final DroppedCounter dropped;

    protected KafkaSignalSubscriber(KafkaTemplate<String, String> kafka,
                                    KafkaExporterProperties props,
                                    DroppedCounter dropped) {
        this.kafka = kafka;
        this.props = props;
        this.dropped = dropped;
    }

    /** 发送 OTLP/JSON 载荷至 topic；失败（同步/异步）仅计数+日志，不向 collector 传播异常。 */
    protected final void send(String topic, String key, String payload, String signal) {
        try {
            kafka.send(topic, key, payload)
                    .whenComplete((metadata, ex) -> {
                        if (ex != null) {
                            dropped.increment();
                            LoggerFactory.getLogger(getClass()).warn(
                                    "Kafka 发送异步失败: signal={} topic={} key={}", signal, topic, key, ex);
                        }
                    });
        } catch (Exception e) {
            // 快速失败语义：max-block-ms 内抛异常，payload 计入 nl.receiver.dropped
            dropped.increment();
            LoggerFactory.getLogger(getClass()).warn(
                    "Kafka 发送同步失败（max-block 快速失败）: signal={} topic={} key={}", signal, topic, key, e);
        }
    }

    /** collector-embedded 硬约束（T01）：onSubscribe 必须立即无界拉取，否则批处理 future 挂起致入口超时。 */
    protected static void requestAll(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }
}

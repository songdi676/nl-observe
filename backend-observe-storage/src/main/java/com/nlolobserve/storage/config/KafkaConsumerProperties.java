package com.nlolobserve.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.kafka.* —— 消费端配置（详设 §1.3/§5）：
 * consumer-group 为消费组名（默认 nl-observe-storage），
 * max-poll-interval-ms 为批处理超时（T08 评审 backport：参数化，避免 Databend 故障时
 * broker 误判消费者死亡重分配分区），经 spring.kafka.consumer.* 同名占位接入 Spring Kafka。
 */
@ConfigurationProperties(prefix = "observe.kafka")
public class KafkaConsumerProperties {

    /** 消费组名（三 topic 共用一组） */
    private String consumerGroup = "nl-observe-storage";

    /** 单批处理超时（ms）：超过则 broker 认为消费者挂掉并重分配分区（默认 5 分钟） */
    private long maxPollIntervalMs = 300_000L;

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public long getMaxPollIntervalMs() {
        return maxPollIntervalMs;
    }

    public void setMaxPollIntervalMs(long maxPollIntervalMs) {
        this.maxPollIntervalMs = maxPollIntervalMs;
    }
}

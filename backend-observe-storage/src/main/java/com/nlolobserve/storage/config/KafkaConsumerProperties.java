package com.nlolobserve.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.kafka.* —— 消费端配置（详设 §1.3/§5）：
 * consumer-group 为消费组名（默认 nl-observe-storage），
 * 经 spring.kafka.consumer.group-id=${observe.kafka.consumer-group} 接入 Spring Kafka。
 */
@ConfigurationProperties(prefix = "observe.kafka")
public class KafkaConsumerProperties {

    /** 消费组名（三 topic 共用一组） */
    private String consumerGroup = "nl-observe-storage";

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }
}

package com.nlolobserve.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import com.nlolobserve.storage.config.KafkaConsumerProperties;
import com.nlolobserve.storage.consumer.LogsConsumer;
import com.nlolobserve.storage.consumer.MetricsConsumer;
import com.nlolobserve.storage.consumer.TracesConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T07 验收（不依赖 Kafka 容器）：
 * - 三独立 listener（traces/metrics/logs 各一个容器）
 * - 消费组 nl-observe-storage
 * - 批处理（listener.type=batch）+ 手动提交（ack-mode manual_immediate）
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.bootstrap-servers=127.0.0.1:59092",
        "observe.databend.url=jdbc:databend://127.0.0.1:8000/observe?presigned_url_disabled=true",
        // T08：启动 SELECT 1 连接自检置 false（本用例不依赖真实 Databend；
        // presigned_url_disabled 校验不豁免，仍由 DatabendPropertiesTest/DatabendStartupCheckTest 覆盖）
        "observe.databend.check-on-startup=false"
})
class StorageContextTest {

    @Autowired
    private KafkaListenerEndpointRegistry endpointRegistry;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private KafkaConsumerProperties consumerProperties;

    @Autowired
    private TracesConsumer tracesConsumer;

    @Autowired
    private MetricsConsumer metricsConsumer;

    @Autowired
    private LogsConsumer logsConsumer;

    @Test
    void threeIndependentListenerContainers_registered() {
        assertThat(endpointRegistry.getListenerContainerIds())
                .containsExactlyInAnyOrder("traces-listener", "metrics-listener", "logs-listener");
        assertThat(endpointRegistry.getListenerContainers()).hasSize(3);
    }

    @Test
    void threeConsumerBeansAreIndependent() {
        assertThat(tracesConsumer).isNotSameAs(metricsConsumer).isNotSameAs(logsConsumer);
        assertThat(metricsConsumer).isNotSameAs(logsConsumer);
    }

    @Test
    void consumerGroupIsNlObserveStorage() {
        assertThat(consumerProperties.getConsumerGroup()).isEqualTo("nl-observe-storage");
        assertThat(kafkaProperties.getConsumer().getGroupId()).isEqualTo("nl-observe-storage");
    }

    @Test
    void batchListenerWithManualAckEnabled() {
        assertThat(kafkaProperties.getListener().getType()).isEqualTo(KafkaProperties.Listener.Type.BATCH);
        assertThat(kafkaProperties.getListener().getAckMode())
                .isEqualTo(org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    }
}

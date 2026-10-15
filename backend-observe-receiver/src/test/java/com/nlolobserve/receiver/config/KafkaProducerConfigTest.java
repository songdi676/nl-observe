package com.nlolobserve.receiver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/**
 * T05 验收 #2（单元）：producer 参数与需求 yaml 逐项一致（NFR-01/NFR-02）。
 * 断言 DefaultKafkaProducerFactory 实际使用的参数 Map：
 * acks=all、idempotence、lz4、linger 5ms、batch 64KB、delivery 20s、request 15s、
 * retries 无限（Integer.MAX_VALUE）、max-in-flight 5、max-block 5s、buffer 64MB。
 * send-timeout 9s 为需求侧参数（kafka-clients 无对应 producer 配置），由 /config/producer 回显断言。
 */
class KafkaProducerConfigTest {

    private static final KafkaExporterProperties P = new KafkaExporterProperties();

    @Test
    void producerProps_matchRequirementYaml() {
        Map<String, Object> props = KafkaProducerConfig.kafkaProducerProps(P);

        assertThat(props).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "10.1.8.33:9092")
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 5)
                .containsEntry(ProducerConfig.BATCH_SIZE_CONFIG, 65536)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20000)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15000)
                .containsEntry(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE)
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000)
                .containsEntry(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864L)
                .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    }

    @Test
    void customPropertiesOverrideDefaultYamlValues() {
        KafkaExporterProperties p = new KafkaExporterProperties();
        p.setBootstrapServers("127.0.0.1:9092");
        p.setAcks("1");
        p.setLingerMs(50);
        p.setBatchSize(1024);
        p.setDeliveryTimeoutMs(10000);
        p.setRequestTimeoutMs(8000);
        p.setRetries(3);
        p.setMaxInFlight(1);
        p.setMaxBlockMs(1000);
        p.setBufferMemory(33554432L);
        p.setSendTimeoutMs(1000);

        Map<String, Object> props = KafkaProducerConfig.kafkaProducerProps(p);

        assertThat(props).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092")
                .containsEntry(ProducerConfig.ACKS_CONFIG, "1")
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 50)
                .containsEntry(ProducerConfig.BATCH_SIZE_CONFIG, 1024)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 8000)
                .containsEntry(ProducerConfig.RETRIES_CONFIG, 3)
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000)
                .containsEntry(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432L);
    }
}

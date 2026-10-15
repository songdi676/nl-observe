package com.nl.observe.poc.receiver;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Plan B：Kafka producer 参数严格按需求 yaml（NFR-01/NFR-02），
 * 供 T05 的 /config/producer 只读断言复用同一参数源。
 */
@Configuration
public class KafkaProducerConfig {

    /**
     * 刻意不声明 @Bean：`Map<String, Object>` 注入点会被 Spring 按「收集容器内全部 bean 成 Map」
     * 解析（value 类型为 Object → 含 otlpTracesController），引发
     * producerFactory → otlpTracesController → kafkaTemplate → producerFactory 循环依赖，
     * 导致 ApplicationContext 无法启动（2026-10-15 私服通路打通后首跑 ExportFailureHookIT 暴露）。
     */
    static Map<String, Object> kafkaProducerProps(KafkaExporterProperties p) {
        Map<String, Object> m = new HashMap<>();
        m.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, p.getBootstrapServers());
        m.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        m.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        m.put(ProducerConfig.ACKS_CONFIG, p.getAcks());
        m.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, p.isIdempotence());
        m.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, p.getCompressionType());
        m.put(ProducerConfig.LINGER_MS_CONFIG, p.getLingerMs());
        m.put(ProducerConfig.BATCH_SIZE_CONFIG, p.getBatchSize());
        m.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, p.getDeliveryTimeoutMs());
        m.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, p.getRequestTimeoutMs());
        m.put(ProducerConfig.RETRIES_CONFIG, p.getRetries());
        m.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, p.getMaxInFlight());
        m.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, p.getMaxBlockMs());
        m.put(ProducerConfig.BUFFER_MEMORY_CONFIG, p.getBufferMemory());
        // 注：kafka-clients 无 send.timeout.ms producer 配置（超时语义由 delivery/request/max.block 覆盖）；
        // sendTimeoutMs 仅作为需求侧参数保留在 KafkaExporterProperties，供 /config/producer 回显对照
        return m;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaExporterProperties p) {
        return new DefaultKafkaProducerFactory<>(kafkaProducerProps(p));
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> factory) {
        return new KafkaTemplate<>(factory);
    }
}

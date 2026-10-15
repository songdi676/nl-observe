package com.nlolobserve.receiver.config;

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
 * Kafka producer 装配（T05，参数严格按需求 yaml——NFR-01/NFR-02，T01 poc-notes §5 复用结论）：
 * acks=all、idempotence、lz4、linger 5ms、batch 64KB、delivery 20s、request 15s、
 * retries 无限、max-in-flight 5、max-block 5s（Kafka 不可达快速失败，AC-10）、buffer 64MB。
 * send-timeout 9s 为需求侧参数（kafka-clients 无对应 producer 配置），仅 /config/producer 回显。
 */
@Configuration
public class KafkaProducerConfig {

    /**
     * 刻意声明为静态方法而非 @Bean：`Map<String, Object>` 类型注入点会被 Spring 解析为
     * 「收集容器内全部 bean 成 Map」，引发 producerFactory → 订阅者 → kafkaTemplate →
     * producerFactory 循环依赖，ApplicationContext 无法启动（T01 POC 首跑暴露，评审 Blocker）。
     */
    public static Map<String, Object> kafkaProducerProps(KafkaExporterProperties p) {
        p.validate();
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

package com.nlolobserve.receiver.web;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T05 验收 #2/#3（集成）：GET /config/producer 返回生效 producer 参数（详设 §2.5），
 * 逐项与需求 yaml 相等（测试可断言）。无需 Kafka 可达（只读回显，不产生发送）；
 * 本用例把 bootstrap 恢复为需求 yaml 值用于断言（无发送 → 不会触发 broker 连接）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProducerConfigControllerTest {

    @DynamicPropertySource
    static void requirementYamlBootstrap(DynamicPropertyRegistry registry) {
        registry.add("observe.kafka.bootstrap-servers", () -> "10.1.8.33:9092");
        // 随机 OTLP 端口：避免与其他测试上下文的 4318 绑定冲突（上下文缓存跨用例存活）
        registry.add("observe.otlp.port", () -> com.nlolobserve.receiver.OtlpHttpEndpointTest.freePort());
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void getProducerConfig_returnsEffectiveParamsMatchingRequirementYaml() {
        ResponseEntity<Map> resp = rest.getForEntity("/config/producer", Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("code")).isEqualTo(0);
        assertThat(body.get("message")).isEqualTo("ok");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data).containsEntry("bootstrapServers", "10.1.8.33:9092")
                .containsEntry("acks", "all")
                .containsEntry("idempotence", true)
                .containsEntry("compressionType", "lz4")
                .containsEntry("lingerMs", 5)
                .containsEntry("batchSize", 65536)
                .containsEntry("deliveryTimeoutMs", 20000)
                .containsEntry("requestTimeoutMs", 15000)
                .containsEntry("retries", 2147483647)
                .containsEntry("maxInFlight", 5)
                .containsEntry("maxBlockMs", 5000)
                .containsEntry("bufferMemory", 67108864)
                .containsEntry("sendTimeoutMs", 9000);
        // topic 名与需求 yaml 一致（路由目标）
        assertThat(data).containsEntry("tracesTopic", "nl-observe.otlp.traces")
                .containsEntry("metricsTopic", "nl-observe.otlp.metrics")
                .containsEntry("logsTopic", "nl-observe.otlp.logs");
    }
}

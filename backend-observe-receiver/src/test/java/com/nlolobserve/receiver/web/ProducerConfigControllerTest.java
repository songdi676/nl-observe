package com.nlolobserve.receiver.web;

import java.util.Map;

import com.nlolobserve.common.constant.Topics;
import com.nlolobserve.receiver.config.KafkaExporterProperties;
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
 * 逐项与需求 yaml 一致（测试可断言）。无需 Kafka 可达（只读回显，不产生发送）。
 * 架构评审 M1：测试上下文绝不得指向真实内网 broker（10.1.8.33:9092）——任何发送路径
 * 被触发即污染生产 topic；本用例 bootstrap 改用不可达占位地址（.invalid 保留 TLD，解析必失败），
 * 需求 yaml 值与 KafkaExporterProperties 默认值的对拍放到不启动 Spring 上下文的用例中。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProducerConfigControllerTest {

    /** 不可达占位地址：DNS 必失败（RFC 2606 .invalid），上下文内任何 producer 操作都打不到真实 broker。 */
    static final String UNREACHABLE_BOOTSTRAP = "producer-echo.invalid:9092";

    @DynamicPropertySource
    static void unreachableBootstrap(DynamicPropertyRegistry registry) {
        registry.add("observe.kafka.bootstrap-servers", () -> UNREACHABLE_BOOTSTRAP);
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
        assertThat(data).containsEntry("bootstrapServers", UNREACHABLE_BOOTSTRAP)
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
                .containsEntry("bufferMemory", 67108864);
        // 需求侧参数单列：sendTimeoutMs 无对应 producer 配置（kafka-clients 无 send.timeout.ms），
        // 只作需求 yaml 回显对照，不参与生效参数断言（评审 Minor）
        @SuppressWarnings("unchecked")
        Map<String, Object> requirementOnly = (Map<String, Object>) data.get("requirementOnly");
        assertThat(requirementOnly).containsEntry("sendTimeoutMs", 9000);
        // topic 名与需求 yaml 一致（路由目标，common Topics 常量）
        assertThat(data).containsEntry("tracesTopic", Topics.TRACES)
                .containsEntry("metricsTopic", Topics.METRICS)
                .containsEntry("logsTopic", Topics.LOGS);
    }

    @Test
    void requirementYamlBootstrap_matchesPropertiesDefault_withoutSpringContext() {
        // 不启动 Spring 上下文：需求 yaml 的 bootstrap 值直接对拍 KafkaExporterProperties 字段默认值，
        // 使「配置回显 == 需求值」的断言与任何真实 broker 地址解耦（评审 M1）
        KafkaExporterProperties defaults = new KafkaExporterProperties();
        assertThat(defaults.getBootstrapServers()).isEqualTo("10.1.8.33:9092");
        assertThat(defaults.getTracesTopic()).isEqualTo("nl-observe.otlp.traces");
        assertThat(defaults.getMetricsTopic()).isEqualTo("nl-observe.otlp.metrics");
        assertThat(defaults.getLogsTopic()).isEqualTo("nl-observe.otlp.logs");
    }
}

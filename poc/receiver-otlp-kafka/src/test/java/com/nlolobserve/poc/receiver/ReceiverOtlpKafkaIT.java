package com.nlolobserve.poc.receiver;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T01 测试用例：poc/receiver-otlp-kafka —— 发样例 OTLP/HTTP → 断言 topic 收到消息。
 * 验收 #1：OTLP/HTTP 4318（测试随机端口）接收 ExportTraceServiceRequest，
 * 成功写入 Testcontainers Kafka 的 nl-observe.otlp.traces。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiverOtlpKafkaIT {

    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("observe.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void otlpHttpTraces_areForwardedToKafkaTopic() throws Exception {
        String payload = sampleTraceJson("C-42", "biz.business.ORDER");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = rest.postForEntity("/v1/traces",
                new HttpEntity<>(payload, headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> consumerProps = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "poc-verify",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var factory = new DefaultKafkaConsumerFactory<String, String>(consumerProps);
        try (var consumer = factory.createConsumer()) {
            consumer.subscribe(Collections.singletonList("nl-observe.otlp.traces"));
            ConsumerRecord<String, String> record = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (record == null && System.currentTimeMillis() < deadline) {
                var rs = consumer.poll(Duration.ofMillis(500));
                if (!rs.isEmpty()) {
                    record = rs.iterator().next();
                }
            }
            assertThat(record).as("topic nl-observe.otlp.traces 应收到消息").isNotNull();
            assertThat(record.key()).isEqualTo("C-42");
            assertThat(record.value()).contains("biz.business.ORDER");
            // 载荷仍是合法 OTLP/JSON
            new ObjectMapper().readTree(record.value())
                    .path("resourceSpans").get(0).path("scopeSpans").get(0)
                    .path("spans").get(0).path("name").asText();
        }
    }

    static String sampleTraceJson(String customerId, String spanName) {
        return """
        {
          "resourceSpans": [{
            "resource": { "attributes": [
              {"key":"service.name","value":{"stringValue":"poc-vue"}},
              {"key":"nl.customer_id","value":{"stringValue":"%s"}}
            ]},
            "scopeSpans": [{ "scope": {"name":"nl-observe-poc"}, "spans": [{
              "traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
              "spanId":"00f067aa0e942000",
              "name":"%s",
              "kind":1,
              "startTimeUnixNano":"1759000000000000000",
              "endTimeUnixNano":"1759000045000000000",
              "attributes":[
                {"key":"biz.code","value":{"stringValue":"ORDER"}},
                {"key":"biz.status","value":{"stringValue":"success"}}
              ],
              "status": {"code":1}
            }]}]
          }]
        }
        """.formatted(customerId, spanName);
    }

    @SuppressWarnings("unused")
    private static Properties unused() { return new Properties(); }
}

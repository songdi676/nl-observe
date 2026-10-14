package com.nlolobserve.poc.receiver;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T01 验收 #2：collector/producer 导出失败的可观测钩子。
 * Plan B 钩子点：KafkaTemplate send whenComplete 异常分支 → Counter nl.receiver.dropped。
 * 故障注入：bootstrap 指向不可达端口（无 Testcontainers），max-block-ms=5000 内快速失败，
 * 断言 OTLP 入口仍返回 200 且 dropped 计数递增（AC-10 语义）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExportFailureHookIT {

    @DynamicPropertySource
    static void deadKafka(DynamicPropertyRegistry registry) {
        // 127.0.0.1:19092 无 broker：send 在 max-block/delivery-timeout 内异常完成
        registry.add("observe.kafka.bootstrap-servers", () -> "127.0.0.1:19092");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MeterRegistry registry;

    @Test
    void kafkaUnreachable_returns200_andDroppedCounterIncrements() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = ReceiverOtlpKafkaIT.sampleTraceJson("C-99", "biz.business.FAIL");

        long before = counterValue();
        ResponseEntity<String> resp = rest.postForEntity("/v1/traces",
                new HttpEntity<>(payload, headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        // 等待 producer 失败回调（max-block-ms=5000 快速失败语义）
        long deadline = System.currentTimeMillis() + 10_000;
        while (counterValue() == before && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(counterValue()).as("nl.receiver.dropped 应递增").isGreaterThan(before);
    }

    private double counterValue() {
        return registry.counter("nl.receiver.dropped").count();
    }
}

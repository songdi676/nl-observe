package com.nlolobserve.receiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.model.OtlpLogPayload;
import com.nlolobserve.common.model.OtlpMetricPayload;
import com.nlolobserve.common.model.OtlpTracePayload;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static com.nlolobserve.common.constant.Topics.LOGS;
import static com.nlolobserve.common.constant.Topics.METRICS;
import static com.nlolobserve.common.constant.Topics.TRACES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T05 验收 #1（集成，env-gated 同 T03 口径）：Testcontainers Kafka 预建环境 + 进程内真实 OTLP 端点
 * （随机端口）→ 发样例 OTLP/JSON → 断言三 topic 消息体（common 模型可解析、字段保真）与 key=nl.customer_id。
 * 评审 Minor-2：消息体用 common 侧 OTLP 模型（Otlp*Payload）解析而非 protobuf JsonFormat——
 * 后者把 bytes 当 base64 解码，而 OTLP/JSON 的 trace/span id 是十六进制字符串，会静默损坏 id 字段；
 * 并新增 hex id 无损往返断言。Docker 不可用或镜像不可得时按 assumption 跳过（内网镜像仓库预拉后关闭）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaThreeTopicsRoutingTest {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaThreeTopicsRoutingTest.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 T01 poc 建议一致（confluent cp-kafka）；内网可用环境变量覆盖为已缓存镜像 */
    private static final String DEFAULT_IMAGE_NAME = "confluentinc/cp-kafka:7.7.1";

    private static final int OTLP_PORT = OtlpHttpEndpointTest.freePort();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** 静态块先于 @DynamicPropertySource 执行：容器启动失败 → null → 动态属性退回默认 + 用例跳过 */
    private static final KafkaContainer KAFKA = tryStartKafka();

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("observe.otlp.port", () -> OTLP_PORT);
        if (KAFKA != null) {
            registry.add("observe.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        }
    }

    @AfterAll
    static void stopKafka() {
        if (KAFKA != null) {
            KAFKA.stop();
        }
    }

    @Test
    void threeSignals_routedToThreeTopics_withCustomerIdKey_andOtlpJsonPayload() throws Exception {
        assumeTrue(KAFKA != null, "Docker/Kafka 镜像不可得：跳过三 topic 集成测试（内网预拉镜像后关闭）");

        HttpResponse<String> tracesResp = postOtlp("/v1/traces", readResource("otlp/sample-traces.json"));
        HttpResponse<String> metricsResp = postOtlp("/v1/metrics", readResource("otlp/sample-metrics.json"));
        HttpResponse<String> logsResp = postOtlp("/v1/logs", readResource("otlp/sample-logs.json"));
        assertThat(tracesResp.statusCode()).isEqualTo(200);
        assertThat(metricsResp.statusCode()).isEqualTo(200);
        assertThat(logsResp.statusCode()).isEqualTo(200);

        // traces：2 span → 2 条消息（每 span 单记录信封），key=CUST-001，载荷可解析为 OTLP/JSON
        List<String> traceMessages = consumeUntil(TRACES, 2);
        assertThat(traceMessages).hasSize(2);
        assertTracesMessage(traceMessages.get(0));

        // metrics：2 data point（gauge + sum）→ 2 条消息
        List<String> metricMessages = consumeUntil(METRICS, 2);
        assertThat(metricMessages).hasSize(2);
        assertMetricsMessage(metricMessages.get(0));

        // logs：1 record → 1 条消息
        List<String> logMessages = consumeUntil(LOGS, 1);
        assertThat(logMessages).hasSize(1);
        assertLogsMessage(logMessages.get(0));
    }

    // ---------- 断言（common 模型解析，与 T08 存储端口径一致） ----------

    private void assertTracesMessage(String message) throws Exception {
        OtlpTracePayload payload = MAPPER.readValue(message, OtlpTracePayload.class);
        OtlpTracePayload.Span span =
                payload.getResourceSpans().get(0).getScopeSpans().get(0).getSpans().get(0);
        assertThat(span.getName()).isIn("biz.business.ORDER", "HTTP POST");
        // OTLP/JSON 的 trace/span id 为 32/16 位十六进制：经 common 模型往返后必须无损（评审 Minor-2）
        assertThat(span.getTraceId()).as("traceId 应为 32 位 hex").matches("[0-9a-f]{32}");
        assertThat(span.getSpanId()).as("spanId 应为 16 位 hex").matches("[0-9a-f]{16}");
        String customer = payload.getResourceSpans().get(0).getResource().getAttributes().stream()
                .filter(kv -> "nl.customer_id".equals(kv.getKey()))
                .findFirst().orElseThrow().getValue().getStringValue();
        assertThat(customer).isEqualTo("CUST-001");
    }

    private void assertMetricsMessage(String message) throws Exception {
        OtlpMetricPayload payload = MAPPER.readValue(message, OtlpMetricPayload.class);
        OtlpMetricPayload.Metric metric =
                payload.getResourceMetrics().get(0).getScopeMetrics().get(0).getMetrics().get(0);
        assertThat(metric.getName()).isIn("nl.first_screen_load", "nl.business_duration");
    }

    private void assertLogsMessage(String message) throws Exception {
        OtlpLogPayload payload = MAPPER.readValue(message, OtlpLogPayload.class);
        assertThat(payload.getResourceLogs().get(0).getScopeLogs().get(0).getLogRecords().get(0)
                .getBody().getStringValue())
                .isEqualTo("order submitted");
    }

    /** 从 topic 消费直到收集到 expectedCount 条（30s 上限；topic 由 producer 自动创建）。 */
    private List<String> consumeUntil(String topic, int expectedCount) {
        Properties consumerProps = new Properties();
        consumerProps.put("bootstrap.servers", KAFKA.getBootstrapServers());
        consumerProps.put("group.id", "assert-" + topic);
        consumerProps.put("auto.offset.reset", "earliest");
        consumerProps.put("key.deserializer", StringDeserializer.class.getName());
        consumerProps.put("value.deserializer", StringDeserializer.class.getName());
        consumerProps.put("max.poll.records", "10");

        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (values.size() < expectedCount && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    assertThat(record.key()).as("消息 key 应等于 nl.customer_id").isEqualTo("CUST-001");
                    values.add(record.value());
                }
            }
        }
        return values;
    }

    // ---------- 脚手架 ----------

    private static KafkaContainer tryStartKafka() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            return null;
        }
        String imageName = envOr("NL_OBSERVE_KAFKA_IMAGE", DEFAULT_IMAGE_NAME);
        try {
            KafkaContainer container = new KafkaContainer(DockerImageName.parse(imageName));
            container.start();
            return container;
        } catch (Throwable t) {
            // 显式告警（评审 m5）：静默吞掉会使「IT 被跳过」在 CI 日志里不可见
            LOG.warn("Kafka 容器启动失败（镜像={}），三 topic 集成测试将跳过", imageName, t);
            return null;
        }
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private HttpResponse<String> postOtlp(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + OTLP_PORT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String readResource(String name) {
        try (InputStream in = KafkaThreeTopicsRoutingTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

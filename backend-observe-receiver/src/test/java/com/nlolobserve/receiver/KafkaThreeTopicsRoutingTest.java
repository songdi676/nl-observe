package com.nlolobserve.receiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
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

import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
 * （随机端口）→ 发样例 OTLP/JSON → 断言三 topic 消息体（OTLP/JSON 可解析、字段保真）与 key=nl.customer_id。
 * Docker 不可用或镜像不可得时按 assumption 跳过（内网镜像仓库预拉后关闭，T01 poc-notes §4.3）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaThreeTopicsRoutingTest {

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

    // ---------- 断言 ----------

    private void assertTracesMessage(String message) throws Exception {
        ExportTraceServiceRequest request = parse(message, ExportTraceServiceRequest.getDefaultInstance());
        var span = request.getResourceSpans(0).getScopeSpans(0).getSpans(0);
        assertThat(span.getName()).isIn("biz.business.ORDER", "HTTP POST");
        String customer = request.getResourceSpans(0).getResource().getAttributesList().stream()
                .filter(kv -> "nl.customer_id".equals(kv.getKey()))
                .findFirst().orElseThrow().getValue().getStringValue();
        assertThat(customer).isEqualTo("CUST-001");
    }

    private void assertMetricsMessage(String message) throws Exception {
        ExportMetricsServiceRequest request = parse(message, ExportMetricsServiceRequest.getDefaultInstance());
        var metric = request.getResourceMetrics(0).getScopeMetrics(0).getMetrics(0);
        assertThat(metric.getName()).isIn("nl.first_screen_load", "nl.business_duration");
    }

    private void assertLogsMessage(String message) throws Exception {
        ExportLogsServiceRequest request = parse(message, ExportLogsServiceRequest.getDefaultInstance());
        assertThat(request.getResourceLogs(0).getScopeLogs(0).getLogRecords(0).getBody().getStringValue())
                .isEqualTo("order submitted");
    }

    /** protobuf 4.x：Parser 不再暴露 newBuilderForType，改以 prototype 取 builder。 */
    private static <T extends com.google.protobuf.Message> T parse(String json, T prototype) throws IOException {
        var builder = prototype.toBuilder();
        JsonFormat.parser().merge(new StringReader(json), builder);
        @SuppressWarnings("unchecked")
        T message = (T) builder.build();
        return message;
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

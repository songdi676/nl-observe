package com.nlolobserve.receiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.nlolobserve.receiver.metrics.DroppedCounter.METRIC_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T05 故障注入（AC-10，NFR-02）：Kafka 不可达（「停 Kafka 容器」的无 Docker 等价口径，
 * T01 poc-notes §4.2 已验证）——bootstrap 指向死端口时：
 * 三信号 OTLP 入口仍返回 200 且响应 <1s（评审 Critical-1 修复后的严格口径：send 已卸载到
 * 专用有界队列执行器，入口只入队即返回，绝不被 producer 的 max-block(5s) 阻塞），
 * 失败计数在后台 sender 线程落地后 nl.receiver.dropped 递增。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaUnreachableDroppedTest {

    private static final int OTLP_PORT = OtlpHttpEndpointTest.freePort();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void deadKafka(DynamicPropertyRegistry registry) {
        registry.add("observe.otlp.port", () -> OTLP_PORT);
        // 127.0.0.1:19092 无 broker：后台 sender 线程上 producer 在 max-block-ms(5000) 内快速失败
        registry.add("observe.kafka.bootstrap-servers", () -> "127.0.0.1:19092");
    }

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void kafkaUnreachable_threeSignalsReturn200_andDroppedIncrements() throws Exception {
        double before = droppedCount();

        for (String signal : List.of("traces", "metrics", "logs")) {
            String path = "/v1/" + signal;
            long t0 = System.currentTimeMillis();
            HttpResponse<String> resp = postOtlp(path, readResource("otlp/sample-" + signal + ".json"));
            long elapsed = System.currentTimeMillis() - t0;
            assertThat(resp.statusCode())
                    .as("OTLP/HTTP %s 在 Kafka 不可达时应仍返回 200（AC-10）", path)
                    .isEqualTo(200);
            // 入口只入队（send 卸载到后台执行器）：响应不得被 broker 阻塞（评审 Critical-1 收紧口径）
            assertThat(elapsed).as("%s 入口响应应快速（<1s）", path).isLessThan(1_000L);
        }

        // 等待后台 sender 线程失败计数落地（每条记录 max-block 内 ~5s，2-4 线程并行；留 35s 上限）
        long deadline = System.currentTimeMillis() + 35_000;
        while (droppedCount() <= before && System.currentTimeMillis() < deadline) {
            sleepQuietly(200);
        }
        assertThat(droppedCount())
                .as("nl.receiver.dropped 应随发送失败递增（traces/metrics/logs 均有失败计数）")
                .isGreaterThan(before);
    }

    private double droppedCount() {
        return meterRegistry.find(METRIC_NAME).counter().count();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        try (InputStream in = KafkaUnreachableDroppedTest.class.getClassLoader().getResourceAsStream(name)) {
            // 资源缺失是测试缺陷而非环境跳过条件，必须失败而非 assumption（评审 m8/Nit）
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

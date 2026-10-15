package com.nlolobserve.receiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.nlolobserve.receiver.config.OtlpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T04 验收 #1（集成）：应用启动后 OTLP/HTTP（observe.otlp.port 可配，此处随机端口）
 * 可接收 /v1/traces 与 /v1/metrics（样例 OTLP/JSON → 200）；未启用 gRPC 4317。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OtlpHttpEndpointTest {

    /** 随机空闲端口：验证端口可配（详设 §1.2 集成测试口径） */
    private static final int OTLP_PORT = freePort();

    @Autowired
    private OtlpProperties otlpProperties;

    @DynamicPropertySource
    static void randomOtlpPort(DynamicPropertyRegistry registry) {
        registry.add("observe.otlp.port", () -> OTLP_PORT);
    }

    @Test
    void otlpPortIsConfigurable() {
        assertThat(otlpProperties.getPort()).isEqualTo(OTLP_PORT);
    }

    @Test
    void postSampleTraces_returns200() throws Exception {
        String body = readResource("otlp/sample-traces.json");
        HttpResponse<String> resp = postOtlp("/v1/traces", body);
        assertThat(resp.statusCode()).as("OTLP/HTTP /v1/traces 应返回 200").isEqualTo(200);
    }

    @Test
    void postSampleMetrics_returns200() throws Exception {
        String body = readResource("otlp/sample-metrics.json");
        HttpResponse<String> resp = postOtlp("/v1/metrics", body);
        assertThat(resp.statusCode()).as("OTLP/HTTP /v1/metrics 应返回 200").isEqualTo(200);
    }

    @Test
    void grpcPort4317_notEnabled() {
        assumeTrue(isPortFree(4317), "4317 已被本机其他进程占用，跳过「未启用」断言");
        assertThatThrownBy(() -> new Socket().connect(new InetSocketAddress("127.0.0.1", 4317), 1000))
                .as("详设 §1.2：OTLP receiver 仅启用 HTTP，gRPC 4317 不得监听")
                .isInstanceOf(ConnectException.class);
    }

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpResponse<String> postOtlp(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + OTLP_PORT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String readResource(String name) {
        try (InputStream in = OtlpHttpEndpointTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int freePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isPortFree(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

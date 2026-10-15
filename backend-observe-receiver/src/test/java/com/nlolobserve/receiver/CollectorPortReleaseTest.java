package com.nlolobserve.receiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static com.nlolobserve.receiver.OtlpHttpEndpointTest.freePort;
import static com.nlolobserve.receiver.OtlpHttpEndpointTest.isPortFree;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T04 验收 #2（集成）：collector 随 SmartLifecycle 启动/停止——
 * 上下文运行期间 OTLP 端口可接收请求；上下文关闭后端口释放（无泄漏，可被重新绑定）。
 */
class CollectorPortReleaseTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void contextClose_releasesOtlpPort() {
        int port = freePort();
        String body;
        try (InputStream in = CollectorPortReleaseTest.class.getClassLoader()
                .getResourceAsStream("otlp/sample-traces.json")) {
            assertThat(in).isNotNull();
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // addFirst 属性源优先级高于 application.yaml（builder.properties 是最低优先级的默认属性）
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ObserveReceiverApplication.class)
                .initializers(ac -> ac.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("otlp-test-port", Map.of("observe.otlp.port", port, "server.port", "0"))))
                .run();
        try {
            // 运行期间：OTLP 端口已监听且可接收
            assertThat(isPortFree(port)).as("运行期间 OTLP 端口 %d 应被监听", port).isFalse();
            int status = postStatus(port, "/v1/traces", body);
            assertThat(status).as("运行期间 POST /v1/traces 应 200").isEqualTo(200);
        } finally {
            ctx.close();
        }

        // 关闭后：端口释放，可被重新绑定
        assertThat(isPortFree(port)).as("上下文关闭后 OTLP 端口 %d 应释放", port).isTrue();
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int postStatus(int port, String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 端口未监听/连接失败：返回 -1 让断言按功能缺失失败
            return -1;
        }
    }
}

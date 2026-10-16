package com.nlolobserve.receiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T06 验收 #1（集成）：限流（observe.security.rate-limit-per-min，滑动窗口按客户端 IP）。
 * 配置 3/分钟：前 3 个请求 200（白名单内正常流量不受影响），第 4、5 个 429（统一错误码 42901）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OtlpSecurityRateLimitTest {

    private static final int OTLP_PORT = OtlpHttpEndpointTest.freePort();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void lowRateLimit(DynamicPropertyRegistry registry) {
        registry.add("observe.otlp.port", () -> OTLP_PORT);
        registry.add("observe.security.rate-limit-per-min", () -> "3");
    }

    @Test
    void overFrequency_returns429_withinWhitelist_returns200() throws Exception {
        String body = readResource("otlp/sample-traces.json");
        for (int i = 1; i <= 3; i++) {
            HttpResponse<String> resp = postOtlp("/v1/traces", body);
            assertThat(resp.statusCode()).as("窗口内第 %d 个请求应 200", i).isEqualTo(200);
        }
        for (int i = 4; i <= 5; i++) {
            HttpResponse<String> resp = postOtlp("/v1/traces", body);
            assertThat(resp.statusCode()).as("超频第 %d 个请求应 429", i).isEqualTo(429);
            JsonNode json = MAPPER.readTree(resp.body());
            assertThat(json.path("code").asInt()).as("429 响应体应为统一错误码 42901").isEqualTo(42901);
        }
    }

    // ---------- 脚手架 ----------

    private HttpResponse<String> postOtlp(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + OTLP_PORT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String readResource(String name) {
        try (InputStream in = OtlpSecurityRateLimitTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

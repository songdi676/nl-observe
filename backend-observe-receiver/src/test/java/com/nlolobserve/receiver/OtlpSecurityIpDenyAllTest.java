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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T06 验收 #1（集成，「mock 非白名单源 IP」的配置口径）：allowed-cidrs 配置为不含回环的
 * 10.99.0.0/16 → 回环来源（127.0.0.1）即"非白名单源 IP"，三个 OTLP 入口 /v1/traces、
 * /v1/metrics、/v1/logs（collector 内嵌 Vert.x HTTP 服务）与 Spring MVC 侧 /config/producer
 * 一律 403（统一错误码 40301），请求体不进入 collector。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OtlpSecurityIpDenyAllTest {

    private static final int OTLP_PORT = OtlpHttpEndpointTest.freePort();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void denyAllWhitelist(DynamicPropertyRegistry registry) {
        registry.add("observe.otlp.port", () -> OTLP_PORT);
        // 不含 127.0.0.1：回环来源即非白名单
        registry.add("observe.security.allowed-cidrs", () -> "10.99.0.0/16");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void nonWhitelistedSource_allThreeOtlpEndpoints_return403() throws Exception {
        String body = readResource("otlp/sample-traces.json");
        for (String path : List.of("/v1/traces", "/v1/metrics", "/v1/logs")) {
            HttpResponse<String> resp = postOtlp(path, body);
            assertThat(resp.statusCode()).as("非白名单源 IP 访问 %s 应 403", path).isEqualTo(403);
            JsonNode json = MAPPER.readTree(resp.body());
            assertThat(json.path("code").asInt())
                    .as("%s 的 403 响应体应为统一错误码 40301", path)
                    .isEqualTo(40301);
        }
    }

    @Test
    void nonWhitelistedSource_configProducer_returns403() throws Exception {
        ResponseEntity<String> resp = rest.getForEntity("/config/producer", String.class);
        assertThat(resp.getStatusCode().value())
                .as("非白名单源 IP 访问 /config/producer 应 403（运维端点不得暴露给白名单外来源）")
                .isEqualTo(403);
        JsonNode json = MAPPER.readTree(resp.getBody());
        assertThat(json.path("code").asInt()).isEqualTo(40301);
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
        try (InputStream in = OtlpSecurityIpDenyAllTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

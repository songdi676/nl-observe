package com.nlolobserve.receiver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T06（配置口径）：max-request-bytes 可配置且以内置过滤器为准——
 * 上限调高到 20MB 时，15MB 合法 OTLP/JSON body 必须 200（collector-embedded 1.2.1 内硬编码
 * 10MB 检查已被 ContextSafe 适配层按可配值取代，否则会误 413）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OtlpSecurityMaxBodyConfigurableTest {

    private static final int OTLP_PORT = OtlpHttpEndpointTest.freePort();

    private static final long TWENTY_MB = 20L * 1024 * 1024;

    private static final int FIFTEEN_MB = 15 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void raiseBodyLimit(DynamicPropertyRegistry registry) {
        registry.add("observe.otlp.port", () -> OTLP_PORT);
        registry.add("observe.security.max-request-bytes", () -> String.valueOf(TWENTY_MB));
    }

    @Test
    void body15Mb_underConfigured20MbLimit_returns200() throws Exception {
        // 超大 body 用 RawHttpPost（写线程+读线程并发），避免 HttpClient 挂死（见其注释）
        RawHttpPost.Result resp = postOtlp("/v1/traces", padTracesToJson(FIFTEEN_MB));
        assertThat(resp.status())
                .as("15MB body 在 20MB 可配上限内应 200（库内 10MB 硬编码检查不得误拒）")
                .isEqualTo(200);
    }

    @Test
    void bodyAboveConfigured20MbLimit_returns413() throws Exception {
        RawHttpPost.Result resp = postOtlp("/v1/traces", padTracesToJson((int) (TWENTY_MB + 1024 * 1024)));
        assertThat(resp.status()).as("超过 20MB 可配上限应 413").isEqualTo(413);
        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.path("code").asInt()).isEqualTo(41301);
    }

    // ---------- 脚手架 ----------

    /** 以 sample-traces.json 为骨架，向 resource.attributes 注入超大 stringValue，得到合法 OTLP/JSON。 */
    private static String padTracesToJson(int targetBytes) throws Exception {
        String sample = readResource("otlp/sample-traces.json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(sample);
        ArrayNode attributes = (ArrayNode) root.path("resourceSpans").path(0)
                .path("resource").path("attributes");
        ObjectNode hugeAttr = MAPPER.createObjectNode();
        hugeAttr.put("key", "nl.padding");
        ObjectNode value = hugeAttr.putObject("value");
        int pad = Math.max(1, targetBytes - MAPPER.writeValueAsString(root).length());
        value.put("stringValue", "x".repeat(pad));
        attributes.add(hugeAttr);
        return MAPPER.writeValueAsString(root);
    }

    private static RawHttpPost.Result postOtlp(String path, String body) throws Exception {
        return RawHttpPost.post(OTLP_PORT, path, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String readResource(String name) throws Exception {
        try (InputStream in = OtlpSecurityMaxBodyConfigurableTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

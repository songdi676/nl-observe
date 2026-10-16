package com.nlolobserve.receiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import java.util.concurrent.Flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mishmash.opentelemetry.server.collector.Span;
import io.mishmash.opentelemetry.server.collector.SpansSubscriber;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * T06 验收 #1（集成）：请求体上限（observe.security.max-request-bytes，测试配置 10485760=10MB）。
 * - 11MB body（Content-Length 口径）→ 413（统一错误码 41301）
 * - 分块（chunked、无 Content-Length）11MB → 413（过滤器按累计字节计数，防 CL 头缺省绕过）
 * - 白名单内正常体量 → 200（正常流量不受影响）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OtlpSecurityMaxBodyTest {

    private static final int OTLP_PORT = OtlpHttpEndpointTest.freePort();

    private static final int TEN_MB = 10 * 1024 * 1024;

    private static final int ELEVEN_MB = 11 * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** 替换 Kafka 写入订阅者：成功通路用例断言端到端投递（同 OtlpSubscriberDeliveryTest 口径） */
    @MockitoBean
    private SpansSubscriber spansSubscriber;

    @DynamicPropertySource
    static void randomOtlpPort(DynamicPropertyRegistry registry) {
        // 上限/白名单用测试 application.yaml 默认值（10MB、127.0.0.1/32）
        registry.add("observe.otlp.port", () -> OTLP_PORT);
    }

    @Test
    void bodyElevenMb_returns413() throws Exception {
        // 超大 body 用 RawHttpPost（写线程+读线程并发）：服务端预检 413 不读 body 时
        // HttpClient 会挂死在 body 写入上（见 RawHttpPost 注释）
        RawHttpPost.Result resp = RawHttpPost.post(OTLP_PORT, "/v1/traces",
                "x".repeat(ELEVEN_MB).getBytes(StandardCharsets.UTF_8));
        assertThat(resp.status()).as("11MB body（>10MB 上限）应 413").isEqualTo(413);
        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.path("code").asInt()).as("413 响应体应为统一错误码 41301").isEqualTo(41301);
    }

    @Test
    void chunkedBodyElevenMb_returns413() throws Exception {
        // 无 Content-Length 的分块上传：过滤器须按累计字节判定，不得因 CL 缺省绕过上限
        byte[] filler = new byte[ELEVEN_MB];
        java.util.Arrays.fill(filler, (byte) 'a');
        int status = chunkedPost("/v1/traces", filler);
        assertThat(status).as("chunked 11MB（>10MB 上限）应 413").isEqualTo(413);
    }

    @Test
    void chunkedBodyWithinLimit_returns200AndDeliveredToSubscriber() throws Exception {
        // T06 评审 Major-1 回归钩子：chunked ≤上限 的正常流量走 ChunkedRequestReplay
        // 重放通路端到端（200 + 订阅者收到投递）——升级 vertx 后重放语义一旦破坏，
        // 全部 chunked 正常流量将静默 500，本用例必红（见 ChunkedRequestReplay javadoc）
        byte[] body = readResource("otlp/sample-traces.json").getBytes(StandardCharsets.UTF_8);
        int status = chunkedPost("/v1/traces", body);
        assertThat(status).as("chunked ≤上限 的合法 OTLP 应 200（重放包装与库通路兼容）").isEqualTo(200);

        // onSubscribe 在上下文启动 subscribe() 时即被调用：从已记录调用取回 Subscription 补请求
        ArgumentCaptor<Flow.Subscription> subscription = ArgumentCaptor.forClass(Flow.Subscription.class);
        verify(spansSubscriber).onSubscribe(subscription.capture());
        subscription.getValue().request(Long.MAX_VALUE);
        verify(spansSubscriber, timeout(3000).atLeastOnce()).onNext(any(Span.class));
    }

    @Test
    void normalBodyWithinWhitelist_returns200() throws Exception {
        HttpResponse<String> resp = postOtlp("/v1/traces", readResource("otlp/sample-traces.json"));
        assertThat(resp.statusCode()).as("白名单内正常体量应 200").isEqualTo(200);
    }

    // ---------- 脚手架 ----------

    private static HttpResponse<String> postOtlp(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + OTLP_PORT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 原始 socket 发送 Transfer-Encoding: chunked 请求（无 Content-Length），返回响应状态码。
     * 体按 1MB 分块写出。分块写入放独立线程（与 RawHttpPost 同理：服务端提前拒绝/断开时
     * 主线程不挂死）。
     */
    private static int chunkedPost(String path, byte[] body) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", OTLP_PORT), 5000);
            socket.setSoTimeout(30_000);
            Thread writer = new Thread(() -> {
                try {
                    OutputStream out = socket.getOutputStream();
                    byte[] head = ("POST " + path + " HTTP/1.1\r\n"
                            + "Host: 127.0.0.1:" + OTLP_PORT + "\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                    out.write(head);

                    int chunk = 1024 * 1024;
                    int written = 0;
                    while (written < body.length) {
                        int n = Math.min(chunk, body.length - written);
                        out.write(hex(n).getBytes(StandardCharsets.UTF_8));
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        out.write(body, written, n);
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        written += n;
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    // 服务端提前断开连接——预期内，忽略
                }
            });
            writer.setDaemon(true);
            writer.start();

            String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertThat(statusLine).isNotNull();
            String[] parts = statusLine.split(" ");
            writer.join(5_000);
            return Integer.parseInt(parts[1]);
        }
    }

    private static String hex(int n) {
        return Integer.toHexString(n);
    }

    private static String readResource(String name) {
        try (InputStream in = OtlpSecurityMaxBodyTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

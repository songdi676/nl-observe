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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

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
        int status = chunkedPost("/v1/traces", ELEVEN_MB);
        assertThat(status).as("chunked 11MB（>10MB 上限）应 413").isEqualTo(413);
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
     * 分块写入放独立线程（与 RawHttpPost 同理：服务端提前拒绝/断开时主线程不挂死）。
     */
    private static int chunkedPost(String path, int totalBytes) throws Exception {
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

                    byte[] filler = new byte[1024 * 1024];
                    java.util.Arrays.fill(filler, (byte) 'a');
                    int chunk = filler.length;
                    int written = 0;
                    while (written + chunk < totalBytes) {
                        out.write(hex(chunk).getBytes(StandardCharsets.UTF_8));
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        out.write(filler);
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        written += chunk;
                    }
                    int rest = totalBytes - written;
                    if (rest > 0) {
                        out.write(hex(rest).getBytes(StandardCharsets.UTF_8));
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        out.write(filler, 0, rest);
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
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

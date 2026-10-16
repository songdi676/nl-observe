package com.nlolobserve.receiver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T06 验收 #2（集成）：白名单内正常流量不受影响——测试 application.yaml 白名单为 127.0.0.1/32，
 * 回环来源的 OTLP 三入口与 /config/producer 均须照常 200。
 * 同时以真实非白名单源 IP（本机非回环网卡地址，绑定客户端 socket 源地址）验证 403：
 * 过滤器按直连口径（Vert.x remoteAddress / servlet getRemoteAddr）判定，不受 X-Forwarded-For 伪造影响。
 * 无可用非回环 IPv4 时该用例按 assumption 跳过（配置口径 403 由 OtlpSecurityIpDenyAllTest 覆盖）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OtlpSecurityIpWhitelistTest {

    private static final int OTLP_PORT = OtlpHttpEndpointTest.freePort();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void randomOtlpPort(DynamicPropertyRegistry registry) {
        registry.add("observe.otlp.port", () -> OTLP_PORT);
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void whitelistedLoopbackOtlp_returns200() throws Exception {
        String body = readResource("otlp/sample-traces.json");
        HttpResponse<String> resp = postOtlp(HttpClient.newHttpClient(), "127.0.0.1", "/v1/traces", body);
        assertThat(resp.statusCode()).as("白名单内（127.0.0.1/32）OTLP 入口应 200").isEqualTo(200);
    }

    @Test
    void whitelistedLoopbackProducerConfig_returns200() {
        ResponseEntity<String> resp = rest.getForEntity("/config/producer", String.class);
        assertThat(resp.getStatusCode().value()).as("白名单内 /config/producer 应 200").isEqualTo(200);
    }

    @Test
    void nonWhitelistedSourceIp_returns403() throws Exception {
        InetAddress src = findNonLoopbackIpv4();
        assumeTrue(src != null, "无可用非回环 IPv4 网卡：跳过真实源 IP 403 用例（配置口径见 OtlpSecurityIpDenyAllTest）");

        // java.net.http.HttpClient 不支持绑定本地源地址：用原始 socket bind(源IP) 发送真实请求，
        // 使服务端 remoteAddress 为真实的非白名单 IP（非 X-Forwarded-For 伪造）
        RawPostResult resp = rawPostBoundTo(src, src.getHostAddress(), "/v1/traces",
                readResource("otlp/sample-traces.json"));
        assertThat(resp.status)
                .as("非白名单源 IP %s 访问 OTLP 入口应 403", src.getHostAddress())
                .isEqualTo(403);
        JsonNode json = MAPPER.readTree(resp.body);
        assertThat(json.path("code").asInt()).as("403 响应体应为统一错误码 40301").isEqualTo(40301);
    }

    // ---------- 脚手架 ----------

    private static HttpResponse<String> postOtlp(HttpClient client, String host, String path, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + OTLP_PORT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 原始 socket：bind(localAddress) + connect，发送 POST 并读回完整响应（状态码 + body）。 */
    private static RawPostResult rawPostBoundTo(InetAddress localAddress, String host, String path, String body)
            throws Exception {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(localAddress, 0));
            socket.connect(new InetSocketAddress(host, OTLP_PORT), 5000);
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + OTLP_PORT + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
            // Connection: close 口径：状态行之后先跳响应头（到空行），再读响应体
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            assertThat(statusLine).isNotNull();
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // 响应头
            }
            StringBuilder bodyBuf = new StringBuilder();
            try {
                while ((line = reader.readLine()) != null) {
                    bodyBuf.append(line).append('\n');
                }
            } catch (IOException e) {
                // 状态行已读回后服务端关闭连接（403 拒绝 + Connection: close → RST）：
                // 已读到的响应体视为完整（拒绝响应体极小，与状态行同段送达）
            }
            return new RawPostResult(status, bodyBuf.toString());
        }
    }

    /** 原始 socket 响应：状态码 + 响应体。 */
    private record RawPostResult(int status, String body) {
    }

    /** 本机第一个非回环 IPv4（如 10.1.8.18/eno1）；无则 null。 */
    private static InetAddress findNonLoopbackIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return null;
    }

    private static String readResource(String name) {
        try (InputStream in = OtlpSecurityIpWhitelistTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

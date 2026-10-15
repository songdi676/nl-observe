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
import java.util.concurrent.Flow;

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
 * T04 端到端投递验证：OTLP/HTTP 入口解析后经内嵌 collector 真实送达订阅者。
 * 同时固化库的 fire-and-forget 语义（T01/T04 poc 实测）：loadBatch 仅将记录入队即返回，
 * 入口 200 表示"已接收并入队"；订阅者侧失败不改变入口状态码——
 * 失败计数（nl.receiver.dropped）是 T05 写入订阅者的职责（AC-10）。
 * 注：用 @MockitoBean 而非嵌套 @Configuration——后者会被 @SpringBootApplication 组件扫描
 * 误装入其他测试上下文（造成多 @Primary 冲突）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OtlpSubscriberDeliveryTest {

    private static final int OTLP_PORT = freePort();

    @MockitoBean
    private SpansSubscriber spansSubscriber;

    @DynamicPropertySource
    static void randomOtlpPort(DynamicPropertyRegistry registry) {
        registry.add("observe.otlp.port", () -> OTLP_PORT);
    }

    @Test
    void postTraces_deliveredToSubscriber_returns200() throws Exception {
        String body = readResource("otlp/sample-traces.json");
        HttpResponse<String> resp = postOtlp("/v1/traces", body);
        assertThat(resp.statusCode())
                .as("OTLP/HTTP 入口应返回 200（fire-and-forget：入队即响应）")
                .isEqualTo(200);

        // onSubscribe 在 subscribe()（上下文启动）时即被调用，测试内 stub 来不及生效，
        // mock 默认不请求 demand → 记录滞留 consumer 队列；从已记录调用取回 Subscription 补请求
        ArgumentCaptor<Flow.Subscription> subscription = ArgumentCaptor.forClass(Flow.Subscription.class);
        verify(spansSubscriber).onSubscribe(subscription.capture());
        subscription.getValue().request(Long.MAX_VALUE);

        // 分发在 SubmissionPublisher worker 线程异步进行，等待送达
        verify(spansSubscriber, timeout(3000).atLeastOnce())
                .onNext(any(Span.class));
    }

    private static HttpResponse<String> postOtlp(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + OTLP_PORT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String readResource(String name) {
        try (InputStream in = OtlpSubscriberDeliveryTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("测试资源 %s 必须存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

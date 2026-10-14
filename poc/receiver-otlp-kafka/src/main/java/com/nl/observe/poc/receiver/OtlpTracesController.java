package com.nlolobserve.poc.receiver;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan B：OTLP/HTTP /v1/traces（JSON 编码的 ExportTraceServiceRequest）→ Kafka traces topic。
 * 导出失败可观测钩子：KafkaTemplate send 的 whenComplete 异常分支 → micrometer Counter
 * "nl.receiver.dropped"（详设 §1.2/§4.2：OTLP 入口对客户端仍返回 200，丢弃有计数）。
 *
 * 注：浏览器 SDK 默认 protobuf 编码；POC 以 JSON 编码验证链路（OTLP/HTTP 规范允许两种编码），
 * protobuf 支持由 Plan A（collector-embedded 原生 receiver）或 T04 引入 opentelemetry-proto 转换。
 */
@RestController
public class OtlpTracesController {

    static final AtomicLong DROPPED = new AtomicLong(); // 测试断言辅助（与 Counter 同步）

    private final KafkaTemplate<String, String> kafka;
    private final KafkaExporterProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Counter dropped;

    public OtlpTracesController(KafkaTemplate<String, String> kafka,
                                KafkaExporterProperties props,
                                MeterRegistry registry) {
        this.kafka = kafka;
        this.props = props;
        this.dropped = registry.counter("nl.receiver.dropped");
    }

    @PostMapping(value = "/v1/traces",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/json"})
    public ResponseEntity<Void> traces(@RequestBody byte[] body) {
        String payload = new String(body, StandardCharsets.UTF_8);
        String key = extractCustomerId(payload);
        kafka.send(props.getTracesTopic(), key, payload)
                .whenComplete((md, ex) -> {
                    if (ex != null) {
                        dropped.increment();
                        DROPPED.incrementAndGet();
                    }
                });
        // 快速失败语义：send() 在 max-block-ms 内入队成功即返回 200；
        // broker 不可达时 whenComplete 异常分支计数（AC-10）
        return ResponseEntity.ok().build();
    }

    /** key = nl.customer_id（详设 §5.3 确定性 key）；缺失时回退空串 key。 */
    String extractCustomerId(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode rs = root.path("resourceSpans");
            if (rs.isArray() && !rs.isEmpty()) {
                JsonNode attrs = rs.get(0).path("resource").path("attributes");
                for (JsonNode a : attrs) {
                    if ("nl.customer_id".equals(a.path("key").asText())) {
                        return a.path("value").path("stringValue").asText("");
                    }
                }
            }
        } catch (Exception ignore) {
            // POC：解析失败不阻断转发（key 退化为空）
        }
        return "";
    }
}

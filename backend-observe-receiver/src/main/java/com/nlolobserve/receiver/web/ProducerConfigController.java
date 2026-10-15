package com.nlolobserve.receiver.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.nlolobserve.common.web.ApiResult;
import com.nlolobserve.receiver.config.KafkaExporterProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /config/producer（详设 §1.2/§2.5）：返回生效 producer 参数 Map（只读），
 * 供运维/Newman 对需求 yaml 逐项断言（T05 验收 #3）。参数源与 KafkaProducerConfig
 * 同一 KafkaExporterProperties，保证回显值与实际生效值一致。
 */
@RestController
public class ProducerConfigController {

    private final KafkaExporterProperties props;

    public ProducerConfigController(KafkaExporterProperties props) {
        this.props = props;
    }

    @GetMapping("/config/producer")
    public ApiResult<Map<String, Object>> producerConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bootstrapServers", props.getBootstrapServers());
        data.put("acks", props.getAcks());
        data.put("idempotence", props.isIdempotence());
        data.put("compressionType", props.getCompressionType());
        data.put("lingerMs", props.getLingerMs());
        data.put("batchSize", props.getBatchSize());
        data.put("deliveryTimeoutMs", props.getDeliveryTimeoutMs());
        data.put("requestTimeoutMs", props.getRequestTimeoutMs());
        data.put("retries", props.getRetries());
        data.put("maxInFlight", props.getMaxInFlight());
        data.put("maxBlockMs", props.getMaxBlockMs());
        data.put("bufferMemory", props.getBufferMemory());
        // 需求侧参数单列（评审 Minor）：sendTimeoutMs 无对应 kafka-clients producer 配置
        // （kafka-clients 无 send.timeout.ms，见 KafkaExporterProperties javadoc），
        // 与生效 producer 参数分置，避免对拍时被误当作生效值断言
        Map<String, Object> requirementOnly = new LinkedHashMap<>();
        requirementOnly.put("sendTimeoutMs", props.getSendTimeoutMs());
        data.put("requirementOnly", requirementOnly);
        // 路由目标 topic（需求 yaml 同块参数，一并回显供断言）
        data.put("tracesTopic", props.getTracesTopic());
        data.put("metricsTopic", props.getMetricsTopic());
        data.put("logsTopic", props.getLogsTopic());
        return ApiResult.ok(data);
    }
}

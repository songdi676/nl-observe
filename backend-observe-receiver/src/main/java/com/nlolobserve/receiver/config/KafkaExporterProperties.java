package com.nlolobserve.receiver.config;

import com.nlolobserve.common.constant.Topics;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.kafka.* —— Kafka producer 参数与 topic 名（详设 §1.2/§5，字段与需求 yaml 一一对应）。
 * 默认值即需求文档的连接参数（bootstrap 以 KAFKA_BOOTSTRAP_SERVERS 环境变量覆盖，NFR-04）；
 * topic 名取 common 侧 {@link Topics} 常量（单一事实源，接收/存储/测试共用，评审 m3）。
 * sendTimeoutMs：kafka-clients 无 send.timeout.ms producer 配置（超时语义由 delivery/request/max.block
 * 覆盖），此处仅作为需求侧参数保留，供 /config/producer 回显对照（T01 poc-notes §1）。
 */
@ConfigurationProperties(prefix = "observe.kafka")
public class KafkaExporterProperties {

    private String bootstrapServers = "10.1.8.33:9092";
    private String tracesTopic = Topics.TRACES;
    private String metricsTopic = Topics.METRICS;
    private String logsTopic = Topics.LOGS;
    private String acks = "all";
    private boolean idempotence = true;
    private String compressionType = "lz4";
    private int lingerMs = 5;
    private int batchSize = 65536;
    private int deliveryTimeoutMs = 20000;
    private int requestTimeoutMs = 15000;
    private int retries = Integer.MAX_VALUE;
    private int maxInFlight = 5;
    private int maxBlockMs = 5000;
    private long bufferMemory = 67108864L;
    private int sendTimeoutMs = 9000;

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTracesTopic() {
        return tracesTopic;
    }

    public void setTracesTopic(String tracesTopic) {
        this.tracesTopic = tracesTopic;
    }

    public String getMetricsTopic() {
        return metricsTopic;
    }

    public void setMetricsTopic(String metricsTopic) {
        this.metricsTopic = metricsTopic;
    }

    public String getLogsTopic() {
        return logsTopic;
    }

    public void setLogsTopic(String logsTopic) {
        this.logsTopic = logsTopic;
    }

    public String getAcks() {
        return acks;
    }

    public void setAcks(String acks) {
        this.acks = acks;
    }

    public boolean isIdempotence() {
        return idempotence;
    }

    public void setIdempotence(boolean idempotence) {
        this.idempotence = idempotence;
    }

    public String getCompressionType() {
        return compressionType;
    }

    public void setCompressionType(String compressionType) {
        this.compressionType = compressionType;
    }

    public int getLingerMs() {
        return lingerMs;
    }

    public void setLingerMs(int lingerMs) {
        this.lingerMs = lingerMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getDeliveryTimeoutMs() {
        return deliveryTimeoutMs;
    }

    public void setDeliveryTimeoutMs(int deliveryTimeoutMs) {
        this.deliveryTimeoutMs = deliveryTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public int getMaxInFlight() {
        return maxInFlight;
    }

    public void setMaxInFlight(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    public int getMaxBlockMs() {
        return maxBlockMs;
    }

    public void setMaxBlockMs(int maxBlockMs) {
        this.maxBlockMs = maxBlockMs;
    }

    public long getBufferMemory() {
        return bufferMemory;
    }

    public void setBufferMemory(long bufferMemory) {
        this.bufferMemory = bufferMemory;
    }

    public int getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(int sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /**
     * 超时自洽校验（评审 Nit）：kafka-clients 要求 delivery.timeout.ms ≥ linger.ms + request.timeout.ms，
     * 否则 producer 启动即抛 ConfigException。本模块未引入 spring-boot-starter-validation（@AssertTrue
     * 会因无 Validator bean 而静默失效），故改为显式 fail-fast，由 KafkaProducerConfig 装配时调用。
     */
    public void validate() {
        if (deliveryTimeoutMs < lingerMs + requestTimeoutMs) {
            throw new IllegalStateException(
                    "observe.kafka 超时配置不自洽：delivery-timeout-ms(" + deliveryTimeoutMs
                            + ") 必须 ≥ linger-ms(" + lingerMs + ") + request-timeout-ms(" + requestTimeoutMs + ")");
        }
    }
}

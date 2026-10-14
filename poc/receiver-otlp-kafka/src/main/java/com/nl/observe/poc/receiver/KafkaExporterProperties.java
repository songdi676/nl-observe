package com.nl.observe.poc.receiver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.kafka.* —— 字段与需求 yaml 一一对应（详设 §1.2 / §5.3）。
 * POC 内联一份（T02 的 backend-observe-common 将提供正式版本）。
 */
@ConfigurationProperties(prefix = "observe.kafka")
public class KafkaExporterProperties {

    private String bootstrapServers = "10.1.8.33:9092";
    private String tracesTopic = "nl-observe.otlp.traces";
    private String metricsTopic = "nl-observe.otlp.metrics";
    private String logsTopic = "nl-observe.otlp.logs";
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

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String v) { this.bootstrapServers = v; }
    public String getTracesTopic() { return tracesTopic; }
    public void setTracesTopic(String v) { this.tracesTopic = v; }
    public String getMetricsTopic() { return metricsTopic; }
    public void setMetricsTopic(String v) { this.metricsTopic = v; }
    public String getLogsTopic() { return logsTopic; }
    public void setLogsTopic(String v) { this.logsTopic = v; }
    public String getAcks() { return acks; }
    public void setAcks(String v) { this.acks = v; }
    public boolean isIdempotence() { return idempotence; }
    public void setIdempotence(boolean v) { this.idempotence = v; }
    public String getCompressionType() { return compressionType; }
    public void setCompressionType(String v) { this.compressionType = v; }
    public int getLingerMs() { return lingerMs; }
    public void setLingerMs(int v) { this.lingerMs = v; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }
    public int getDeliveryTimeoutMs() { return deliveryTimeoutMs; }
    public void setDeliveryTimeoutMs(int v) { this.deliveryTimeoutMs = v; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int v) { this.requestTimeoutMs = v; }
    public int getRetries() { return retries; }
    public void setRetries(int v) { this.retries = v; }
    public int getMaxInFlight() { return maxInFlight; }
    public void setMaxInFlight(int v) { this.maxInFlight = v; }
    public int getMaxBlockMs() { return maxBlockMs; }
    public void setMaxBlockMs(int v) { this.maxBlockMs = v; }
    public long getBufferMemory() { return bufferMemory; }
    public void setBufferMemory(long v) { this.bufferMemory = v; }
    public int getSendTimeoutMs() { return sendTimeoutMs; }
    public void setSendTimeoutMs(int v) { this.sendTimeoutMs = v; }
}

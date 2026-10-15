package com.nlolobserve.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.otlp.* —— OTLP/HTTP 接收端口（详设 §1.2/§5，默认 4318，可配）。
 * gRPC 4317 不启用（详设 §1.2：OTLP receiver 仅启用 HTTP），故无相关开关。
 */
@ConfigurationProperties(prefix = "observe.otlp")
public class OtlpProperties {

    /** OTLP/HTTP 监听端口，默认 4318 */
    private int port = 4318;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}

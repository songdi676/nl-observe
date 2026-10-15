package com.nl.observe.poc.receiver;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 丢弃计数（nl.receiver.dropped）的注册点。
 * micrometer-core 单独不提供自动装配的 MeterRegistry（自动装配在 spring-boot-actuator-autoconfigure），
 * 无 actuator 的 POC 需显式定义 SimpleMeterRegistry（2026-10-15 首跑 ExportFailureHookIT 暴露）。
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public SimpleMeterRegistry simpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}

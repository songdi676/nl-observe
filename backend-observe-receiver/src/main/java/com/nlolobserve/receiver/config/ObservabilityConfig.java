package com.nlolobserve.receiver.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 丢弃计数（nl.receiver.dropped，详设 §1.2 metrics/）的注册点（T05）。
 * micrometer-core 单独不提供自动装配的 MeterRegistry（自动装配在 actuator 模块），
 * 无 actuator 时须显式定义 SimpleMeterRegistry（T01 POC 首跑 ExportFailureHookIT 暴露）。
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public SimpleMeterRegistry simpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}

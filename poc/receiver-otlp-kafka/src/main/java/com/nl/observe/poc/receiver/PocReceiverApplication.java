package com.nl.observe.poc.receiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * T01 POC：Spring Boot 3 / JDK17 进程内 OTLP/HTTP 接收 → Kafka。
 * Plan A（collector-embedded 1.2.1 进程内启动）由 CollectorEmbeddedProbeTest 探针确认 API；
 * Plan B（Spring MVC + KafkaTemplate）为可运行兜底实现，producer 参数与需求 yaml 一致。
 */
@SpringBootApplication
@EnableConfigurationProperties(KafkaExporterProperties.class)
public class PocReceiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(PocReceiverApplication.class, args);
    }
}

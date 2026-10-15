package com.nlolobserve.receiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * backend-observe-receiver 启动类（T04）。
 * OTLP/HTTP 端点由内嵌 collector 在 observe.otlp.port（默认 4318）监听（独立于 servlet 端口）；
 * servlet 端口供 T05 的 /config/producer 与 T06 安全过滤器使用。
 * 不用 @SpringBootApplication 聚合注解：其 proxyBeanMethods=true 的 CGLIB 增强要求可见构造器，
 * 与 checkstyle（P3C HideUtilityClassConstructor）要求的私有构造器冲突；
 * 按 Spring Boot 参考文档改用等价三注解 + lite 模式（本类无 @Bean 方法，无影响）。
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan
@ConfigurationPropertiesScan
public class ObserveReceiverApplication {

    private ObserveReceiverApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(ObserveReceiverApplication.class, args);
    }
}

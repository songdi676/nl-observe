package com.nlolobserve.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * backend-observe-storage 启动类（T07）。
 * 无 HTTP 端点（spring-boot-starter 核心，非 web）；进程由 Kafka listener 容器线程保持存活。
 * 不用 @SpringBootApplication 聚合注解：其 proxyBeanMethods=true 的 CGLIB 增强要求可见构造器，
 * 与 checkstyle（P3C HideUtilityClassConstructor）要求的私有构造器冲突；
 * 按 Spring Boot 参考文档改用等价三注解 + lite 模式（本类无 @Bean 方法，无影响）。
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan
@ConfigurationPropertiesScan
public class ObserveStorageApplication {

    private ObserveStorageApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(ObserveStorageApplication.class, args);
    }
}

package com.nlolobserve.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * backend-observe-query 启动类（T09）。
 * servlet 端口承载 /api/observe/** 查询 API（详设 §1.4/§2）；本服务只读 Databend、无副作用。
 * 不用 @SpringBootApplication 聚合注解：其 proxyBeanMethods=true 的 CGLIB 增强要求可见构造器，
 * 与 checkstyle（P3C HideUtilityClassConstructor）要求的私有构造器冲突；
 * 按 Spring Boot 参考文档改用等价三注解 + lite 模式（本类无 @Bean 方法，无影响）。
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan
@ConfigurationPropertiesScan
public class ObserveQueryApplication {

    private ObserveQueryApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(ObserveQueryApplication.class, args);
    }
}

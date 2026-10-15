package com.nlolobserve.query.config;

import org.springframework.context.annotation.Configuration;

/**
 * 鉴权空壳（详设 §1.4）：web-ui 与 API 同经业务系统既有网关（外部边界依赖），
 * 本服务不内置鉴权逻辑，仅审计（web.AuditLogFilter 对 /api/observe/** 记录
 * method/path/customerId/traceId 至独立 audit 日志文件）。落地方式待澄清 #6；
 * 后续引入鉴权时在此补充 SecurityFilterChain 装配，勿散落到各 Controller。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private SecurityConfig() {
    }
}

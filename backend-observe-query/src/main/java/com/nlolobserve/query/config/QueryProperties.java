package com.nlolobserve.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.* —— 查询端运行时配置（详设 §5）。
 * 本模块 databend 连接项见 {@link DatabendProperties}（独立前缀 observe.databend）。
 */
@ConfigurationProperties(prefix = "observe")
public class QueryProperties {

    /** 查询超时（毫秒），经 spring.jdbc.template.query-timeout 施加到 JdbcTemplate（详设 §4.2：查询超时 10s） */
    private long queryTimeoutMs = 10000;

    /** 运维视图（/metrics/summary 不带 customerId）限流：每分钟最大请求数（详设 §1.4） */
    private int opsViewRateLimitPerMin = 60;

    public long getQueryTimeoutMs() {
        return queryTimeoutMs;
    }

    public void setQueryTimeoutMs(long queryTimeoutMs) {
        this.queryTimeoutMs = queryTimeoutMs;
    }

    public int getOpsViewRateLimitPerMin() {
        return opsViewRateLimitPerMin;
    }

    public void setOpsViewRateLimitPerMin(int opsViewRateLimitPerMin) {
        this.opsViewRateLimitPerMin = opsViewRateLimitPerMin;
    }
}

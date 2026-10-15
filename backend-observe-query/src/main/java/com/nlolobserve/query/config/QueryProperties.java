package com.nlolobserve.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.* —— 查询端运行时配置（详设 §5）。
 * 本模块 databend 连接项见 {@link DatabendProperties}（独立前缀 observe.databend）。
 * 查询超时不在本类：单一事实来源为 yaml 的 observe.query-timeout-ms，
 * 经 spring.jdbc.template.query-timeout 插值生效（评审 m2：删死字段防双源漂移）。
 */
@ConfigurationProperties(prefix = "observe")
public class QueryProperties {

    /** 运维视图（/metrics/summary 不带 customerId）限流：每分钟最大请求数（详设 §1.4） */
    private int opsViewRateLimitPerMin = 60;

    public int getOpsViewRateLimitPerMin() {
        return opsViewRateLimitPerMin;
    }

    public void setOpsViewRateLimitPerMin(int opsViewRateLimitPerMin) {
        this.opsViewRateLimitPerMin = opsViewRateLimitPerMin;
    }
}

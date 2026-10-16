package com.nlolobserve.receiver.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.security.* —— 接收端入口安全过滤器参数（详设 §1.2/§5，T06，质疑者2 R1 入口防伪造/DoS）。
 * 字段与需求 yaml 一一对应：
 * - allowed-cidrs：IP 白名单（CIDR 列表，逗号分隔环境变量 OBSERVE_ALLOWED_CIDRS 可覆盖）；
 *   按直连口径判定（Vert.x remoteAddress / servlet getRemoteAddr），不信任 X-Forwarded-For（防伪造）。
 * - max-request-bytes：请求体上限，默认 10485760（10MB，详设 §5）。
 * - rate-limit-per-min：每客户端 IP 每分钟请求上限（滑动窗口），默认 600（详设 §5）。
 */
@ConfigurationProperties(prefix = "observe.security")
public class SecurityFilterProperties {

    /** 白名单 CIDR（默认内网段 10.0.0.0/8，详设 §5；空列表=拒绝一切，fail-closed） */
    private List<String> allowedCidrs = List.of("10.0.0.0/8");

    /** 请求体上限（字节），超限 413 */
    private long maxRequestBytes = 10485760L;

    /** 每客户端 IP 每分钟请求上限，超限 429 */
    private long rateLimitPerMin = 600L;

    /** 防御性拷贝：不向调用方暴露内部可变列表（null 等价空列表=拒绝一切，fail-closed）。 */
    public List<String> getAllowedCidrs() {
        return allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
    }

    public void setAllowedCidrs(List<String> allowedCidrs) {
        this.allowedCidrs = allowedCidrs == null ? null : List.copyOf(allowedCidrs);
    }

    public long getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public long getRateLimitPerMin() {
        return rateLimitPerMin;
    }

    public void setRateLimitPerMin(long rateLimitPerMin) {
        this.rateLimitPerMin = rateLimitPerMin;
    }

    /**
     * 启动期 fail-fast 校验（安全口径：非法/缺失的安全参数不得静默降级为放行）。
     */
    public void validate() {
        if (maxRequestBytes <= 0) {
            throw new IllegalStateException("observe.security.max-request-bytes 必须 > 0");
        }
        if (rateLimitPerMin <= 0) {
            throw new IllegalStateException("observe.security.rate-limit-per-min 必须 > 0");
        }
    }
}

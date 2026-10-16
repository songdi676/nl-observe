package com.nlolobserve.receiver.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.web.ApiResult;
import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.receiver.config.SecurityFilterProperties;
import com.nlolobserve.receiver.metrics.RejectedCounter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spring MVC 侧 IP 白名单过滤器（T06，详设 §1.2 filter/IpWhitelistFilter、§2.5）：
 * 只读运维端点（/config/producer）不得暴露给白名单外来源——与 OTLP 入口共用
 * observe.security.allowed-cidrs 白名单（CIDR 匹配内核 {@link CidrMatcher}）。
 * 按直连口径 request.getRemoteAddr() 判定，不读取 X-Forwarded-For（防伪造）；
 * 非白名单 → 403 + 统一错误码 40301（详设 §4.1），并计 nl.receiver.rejected{reason=ip}
 * （与 OTLP 入口同指标同标签，docs/ops/receiver-alerting.md 的"OTLP/MVC 入口"告警口径由此闭环）。
 * 体积上限/限流仅适用于 OTLP 入口（{@link OtlpSecurityFilter}）：运维端点为 GET 只读，
 * 详设 §2.4 的运维视图限流桶属查询端（T10+），不在本过滤器职责内。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IpWhitelistFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(IpWhitelistFilter.class);

    private final CidrMatcher cidrMatcher;
    private final RejectedCounter rejectedCounter;
    private final ObjectMapper objectMapper;

    public IpWhitelistFilter(SecurityFilterProperties properties,
                             RejectedCounter rejectedCounter,
                             ObjectMapper objectMapper) {
        properties.validate();
        this.cidrMatcher = CidrMatcher.of(properties.getAllowedCidrs());
        this.rejectedCounter = rejectedCounter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http && response instanceof HttpServletResponse servletResponse) {
            String remoteAddr = http.getRemoteAddr();
            if (!cidrMatcher.isAllowed(remoteAddr)) {
                LOG.warn("MVC 入口白名单拒绝: remote={} path={}", remoteAddr, http.getRequestURI());
                rejectedCounter.increment(RejectedCounter.REASON_IP);
                writeForbidden(servletResponse);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.IP_FORBIDDEN.getHttpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ApiResultJson.toJson(ApiResult.fail(ErrorCode.IP_FORBIDDEN), objectMapper));
    }
}

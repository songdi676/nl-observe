package com.nlolobserve.query.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 审计日志过滤器（详设 §1.4 安全项）：仅对 /api/observe/** 记录
 * method/path/customerId/traceId 到独立 audit 日志文件（logback AUDIT appender，
 * 文件名 logging.audit.file，默认 logs/audit/audit.log），与业务日志隔离、防误删业务日志。
 *
 * customerId 取值：路径 /api/observe/customers/{customerId} 优先，其次 customerId 查询参数
 * （/metrics/summary 运维视图显式指定时）；均无记 "-"。
 * traceId 取值：请求头 X-Trace-Id（网关透传）优先，缺省生成 UUID 并写入 MDC 供同请求业务日志关联。
 */
@Component
public class AuditLogFilter extends OncePerRequestFilter {

    /** 审计日志 logger 名（logback-spring.xml 中绑定独立 AUDIT 文件 appender） */
    public static final String AUDIT_LOGGER = "AUDIT";

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    public static final String CUSTOMER_ID_PARAM = "customerId";

    public static final String ABSENT = "-";

    private static final String AUDIT_PATH_PREFIX = "/api/observe/";

    private static final Pattern CUSTOMER_PATH = Pattern.compile("^/api/observe/customers/([^/]+)");

    private static final Logger AUDIT = LoggerFactory.getLogger(AUDIT_LOGGER);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(AUDIT_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            AUDIT.info("method={} path={} customerId={} traceId={} status={}",
                    request.getMethod(), request.getRequestURI(),
                    resolveCustomerId(request), traceId, response.getStatus());
            MDC.remove("traceId");
        }
    }

    private static String resolveTraceId(HttpServletRequest request) {
        String header = request.getHeader(TRACE_ID_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        return UUID.randomUUID().toString();
    }

    private static String resolveCustomerId(HttpServletRequest request) {
        Matcher matcher = CUSTOMER_PATH.matcher(request.getRequestURI());
        if (matcher.lookingAt()) {
            return matcher.group(1);
        }
        String param = request.getParameter(CUSTOMER_ID_PARAM);
        if (param != null && !param.isBlank()) {
            return param;
        }
        return ABSENT;
    }
}

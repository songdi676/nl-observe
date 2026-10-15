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
 * method/path/srcIp/customerId/traceId/traceSrc/status 到独立 audit 日志文件
 * （logback AUDIT appender，文件名 logging.audit.file，默认 logs/audit/audit.log），
 * 与业务日志隔离、防误删业务日志。
 *
 * 取值与防注入口径（T09 评审 Major-1/安全 F2-F3/F9）：
 * - customerId：路径 /api/observe/customers/{customerId} 优先，其次 customerId 查询参数
 *   （/metrics/summary 运维视图显式指定时）；均无记 "-"。控制字符（含 %0A 解码后的换行）
 *   一律替换为 "_"，防审计行注入伪造整行记录；
 * - traceId：X-Trace-Id 头仅接受 ^[A-Za-z0-9._-]{1,128}$（不合法回退生成 UUID），
 *   traceSrc 标 header|gen；头值本身经控制字符清洗后入日志；
 * - srcIp：request.getRemoteAddr()（网关透传 XFF 的解析为鉴权落地后的运维项）；
 * - 过滤判定用 getServletPath()（去 context path，防非根 context path 部署整体旁路）。
 */
@Component
public class AuditLogFilter extends OncePerRequestFilter {

    /** 审计日志 logger 名（logback-spring.xml 中绑定独立 AUDIT 文件 appender） */
    public static final String AUDIT_LOGGER = "AUDIT";

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    public static final String CUSTOMER_ID_PARAM = "customerId";

    public static final String ABSENT = "-";

    /** X-Trace-Id 受信格式（白名单外回退生成，安全 F2：防调用方伪造关联 ID） */
    public static final Pattern TRACE_ID_HEADER_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private static final String AUDIT_PATH_PREFIX = "/api/observe/";

    private static final Pattern CUSTOMER_PATH = Pattern.compile("^/api/observe/customers/([^/]+)");

    private static final Logger AUDIT = LoggerFactory.getLogger(AUDIT_LOGGER);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 审计判定用不含 context path 的路径（安全 F9：非根 context path 部署不旁路审计）
        return !auditPath(request).startsWith(AUDIT_PATH_PREFIX);
    }

    /**
     * 不含 context path 的请求路径：优先 getServletPath()（真实容器中容器拆分后恒非空）；
     * 空串仅出现于模拟 servlet 容器（MockMvc 单测不拆分路径），回退 requestURI。
     */
    private static String auditPath(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            return request.getRequestURI();
        }
        return path;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        ResolvedTraceId resolved = resolveTraceId(request);
        MDC.put("traceId", resolved.id());
        try {
            filterChain.doFilter(request, response);
        } finally {
            AUDIT.info("method={} path={} srcIp={} customerId={} traceId={} traceSrc={} status={}",
                    request.getMethod(), auditPath(request), request.getRemoteAddr(),
                    resolveCustomerId(request), resolved.id(), resolved.source(), response.getStatus());
            MDC.remove("traceId");
        }
    }

    private record ResolvedTraceId(String id, String source) {
    }

    private static ResolvedTraceId resolveTraceId(HttpServletRequest request) {
        String header = request.getHeader(TRACE_ID_HEADER);
        if (header != null && TRACE_ID_HEADER_PATTERN.matcher(header.trim()).matches()) {
            return new ResolvedTraceId(header.trim(), "header");
        }
        if (header != null && !header.isBlank()) {
            // 不合法头值：忽略内容、生成 UUID（安全 F2：头值任意伪造 traceId 污染取证关联）
            return new ResolvedTraceId(UUID.randomUUID().toString(), "gen");
        }
        return new ResolvedTraceId(UUID.randomUUID().toString(), "gen");
    }

    private static String resolveCustomerId(HttpServletRequest request) {
        Matcher matcher = CUSTOMER_PATH.matcher(auditPath(request));
        if (matcher.lookingAt()) {
            return stripControlChars(matcher.group(1));
        }
        String param = request.getParameter(CUSTOMER_ID_PARAM);
        if (param != null && !param.isBlank()) {
            return stripControlChars(param);
        }
        return ABSENT;
    }

    /** 控制字符（含换行/回车）替换为 "_"：防 %0A 解码后在审计文件注入伪造行（Major-1）。 */
    static String stripControlChars(String value) {
        return value.replaceAll("\\p{Cntrl}", "_");
    }
}

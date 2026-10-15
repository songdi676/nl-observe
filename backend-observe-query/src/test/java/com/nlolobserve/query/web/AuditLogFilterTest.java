package com.nlolobserve.query.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.nlolobserve.common.dto.BusinessItem;
import com.nlolobserve.query.repo.QueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T09 测试用例（集成）：调用任一 /api/observe/** API → 独立 audit 日志文件含对应行
 * （method/path/customerId/traceId；详设 §1.4 验收 #2）。
 * Databend 连接自检经属性关闭（骨架口径，真实库 IT 属 T22/Newman 范围）。
 */
@SpringBootTest(properties = {
        "observe.databend.url=jdbc:databend://localhost:8000/observe?presigned_url_disabled=true",
        "observe.databend.check-on-startup=false",
        "logging.audit.file=target/audit/audit-it.log"
})
@AutoConfigureMockMvc
class AuditLogFilterTest {

    private static final Path AUDIT_FILE = Path.of("target/audit/audit-it.log");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryRepository repository;

    @Test
    void businessesApi_appendsAuditLineWithAllFields() throws Exception {
        BusinessItem item = new BusinessItem();
        item.setBizId("b-001");
        when(repository.findBusinessItems(eq("c-1"), any(), any(), any())).thenReturn(List.of(item));

        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-28")
                        .header(AuditLogFilter.TRACE_ID_HEADER, "trace-fixed-1"))
                .andExpect(status().isOk());

        String audit = readAudit();
        assertThat(audit)
                .contains("method=GET")
                .contains("path=/api/observe/customers/c-1/businesses")
                .contains("customerId=c-1")
                .contains("traceId=trace-fixed-1")
                .contains("traceSrc=header");
    }

    @Test
    void metricsSummary_customerIdFromQueryParam() throws Exception {
        when(repository.findFirstScreenLoadP95(any(), any(), eq("c-9"))).thenReturn(100.0);
        // 参数序 (from, to, customerId, bizCode)（评审 m5 统一签名）
        when(repository.findPerBizMetrics(any(), any(), eq("c-9"), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/observe/metrics/summary")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-28")
                        .param(AuditLogFilter.CUSTOMER_ID_PARAM, "c-9"))
                .andExpect(status().isOk());

        assertThat(readAudit()).contains("customerId=c-9");
    }

    @Test
    void missingTraceIdHeader_generatesUuid() throws Exception {
        when(repository.findBusinessItems(eq("c-2"), any(), any(), any())).thenReturn(List.of(new BusinessItem()));

        mockMvc.perform(get("/api/observe/customers/c-2/businesses")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-28"))
                .andExpect(status().isOk());

        String audit = readAudit();
        assertThat(audit).containsPattern("traceId=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(audit).contains("traceSrc=gen");
    }

    @Test
    void invalidTraceIdHeader_fallsBackToGeneratedUuid() throws Exception {
        when(repository.findBusinessItems(eq("c-3"), any(), any(), any())).thenReturn(List.of(new BusinessItem()));

        // 不合法头值（含空白/换行）：忽略内容、traceSrc=gen，头值不得原样入审计行（安全 F2）
        mockMvc.perform(get("/api/observe/customers/c-3/businesses")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-28")
                        .header(AuditLogFilter.TRACE_ID_HEADER, "evil trace\nid"))
                .andExpect(status().isOk());

        String audit = readAudit();
        assertThat(audit).contains("traceSrc=gen");
        assertThat(audit).containsPattern("traceId=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(audit).doesNotContain("evil trace");
    }

    @Test
    void customerIdWithNewline_sanitizedInAuditLine_noForgedLine() throws Exception {
        when(repository.findFirstScreenLoadP95(any(), any(), any())).thenReturn(100.0);
        when(repository.findPerBizMetrics(any(), any(), any(), any())).thenReturn(List.of());

        // %0A 解码后的换行若原样落盘可伪造整行审计记录（Major-1）：控制字符替换为 _
        mockMvc.perform(get("/api/observe/metrics/summary")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-28")
                        .param(AuditLogFilter.CUSTOMER_ID_PARAM, "c-9\nFORGED_LINE method=GET path=/api/observe/customers/attacker/businesses"))
                .andExpect(status().isOk());

        String audit = readAudit();
        assertThat(audit).contains("customerId=c-9_FORGED_LINE");
        assertThat(audit).doesNotContain("\nFORGED_LINE method=GET");
        // 审计文件不得出现以伪造字段开头的独立行（伪造行无时间戳前缀）
        for (String line : audit.split("\n")) {
            assertThat(line).isNotEqualTo("FORGED_LINE method=GET");
        }
    }

    @Test
    void nonObservePath_notAudited() throws Exception {
        mockMvc.perform(get("/not-observe/path")).andExpect(status().isNotFound());
        assertThat(readAudit()).doesNotContain("path=/not-observe/path");
    }

    private static String readAudit() throws Exception {
        assertThat(Files.exists(AUDIT_FILE)).as("独立 audit 日志文件必须存在（logback AUDIT appender）").isTrue();
        return Files.readString(AUDIT_FILE, StandardCharsets.UTF_8);
    }
}

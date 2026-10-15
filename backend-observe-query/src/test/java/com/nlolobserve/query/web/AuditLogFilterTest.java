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
                .contains("traceId=trace-fixed-1");
    }

    @Test
    void metricsSummary_customerIdFromQueryParam() throws Exception {
        when(repository.findFirstScreenLoadP95(any(), any(), eq("c-9"))).thenReturn(100.0);
        when(repository.findPerBizMetrics(any(), any(), any(), eq("c-9"))).thenReturn(List.of());

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

        assertThat(readAudit()).containsPattern("traceId=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
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

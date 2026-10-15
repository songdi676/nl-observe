package com.nlolobserve.query.web;

import java.util.List;
import java.util.Optional;

import com.nlolobserve.common.dto.BizMetric;
import com.nlolobserve.common.dto.BusinessItem;
import com.nlolobserve.common.dto.SpanDetail;
import com.nlolobserve.common.dto.SpanNode;
import com.nlolobserve.query.repo.QueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T09 集成：/api/observe/** API 响应契约（详设 §2/§4.1）——
 * 正常 0、参数错误 40001/400、无数据 40401/404、限流 42901/429、超时/内部错误 50000/500。
 */
@SpringBootTest(properties = {
        "observe.databend.url=jdbc:databend://localhost:8000/observe?presigned_url_disabled=true",
        "observe.databend.check-on-startup=false",
        "observe.ops-view-rate-limit-per-min=2",
        "logging.audit.file=target/audit/audit-api-it.log"
})
@AutoConfigureMockMvc
class QueryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryRepository repository;

    @Test
    void businesses_returnsItems() throws Exception {
        BusinessItem item = new BusinessItem();
        item.setBizId("b-001");
        item.setBizCode("ORDER");
        item.setTraceId("4bf92f35");
        when(repository.findBusinessItems(eq("c-1"), any(), any(), any())).thenReturn(List.of(item));

        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-01").param("to", "2026-09-28").param("bizCode", "ORDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].bizId").value("b-001"))
                .andExpect(jsonPath("$.data[0].bizCode").value("ORDER"));
    }

    @Test
    void businesses_missingFrom_returns40001() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses").param("to", "2026-09-28"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void businesses_spanOver31Days_returns40001() throws Exception {
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-01").param("to", "2026-10-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void businesses_empty_returns40401() throws Exception {
        when(repository.findBusinessItems(any(), any(), any(), any())).thenReturn(List.of());
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-01").param("to", "2026-09-28"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void trace_returnsSpanTree() throws Exception {
        SpanNode node = new SpanNode();
        node.setSpanId("s1");
        node.setName("biz.business.ORDER");
        when(repository.findSpans("c-1", "t-1")).thenReturn(List.of(node));

        mockMvc.perform(get("/api/observe/customers/c-1/traces/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.traceId").value("t-1"))
                .andExpect(jsonPath("$.data.spans[0].spanId").value("s1"));
    }

    @Test
    void trace_missing_returns40401() throws Exception {
        when(repository.findSpans(any(), any())).thenReturn(List.of());
        mockMvc.perform(get("/api/observe/customers/c-1/traces/t-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void spanDetail_returnsDetail() throws Exception {
        SpanDetail detail = new SpanDetail();
        detail.setSpanId("s1");
        detail.setBizCode("ORDER");
        when(repository.findSpan("c-1", "t-1", "s1")).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/api/observe/customers/c-1/traces/t-1/spans/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spanId").value("s1"))
                .andExpect(jsonPath("$.data.bizCode").value("ORDER"));
    }

    @Test
    void spanDetail_missing_returns40401() throws Exception {
        when(repository.findSpan(any(), any(), any())).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/observe/customers/c-1/traces/t-1/spans/s-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void metricsSummary_withCustomer_returnsSummary() throws Exception {
        when(repository.findFirstScreenLoadP95(any(), any(), eq("c-1"))).thenReturn(1830.5);
        BizMetric metric = new BizMetric();
        metric.setBizCode("ORDER");
        metric.setCount(552);
        when(repository.findPerBizMetrics(any(), any(), any(), eq("c-1"))).thenReturn(List.of(metric));

        mockMvc.perform(get("/api/observe/metrics/summary")
                        .param("from", "2026-09-01").param("to", "2026-09-28").param("customerId", "c-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.firstScreenLoadP95").value(1830.5))
                .andExpect(jsonPath("$.data.perBiz[0].bizCode").value("ORDER"));
    }

    @Test
    void metricsSummary_opsView_overLimit_returns42901() throws Exception {
        when(repository.findFirstScreenLoadP95(any(), any(), any())).thenReturn(100.0);
        when(repository.findPerBizMetrics(any(), any(), any(), any())).thenReturn(List.of());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/observe/metrics/summary")
                            .param("from", "2026-09-01").param("to", "2026-09-28"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/observe/metrics/summary")
                        .param("from", "2026-09-01").param("to", "2026-09-28"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));
    }

    @Test
    void dataAccessFailure_returns50000() throws Exception {
        when(repository.findBusinessItems(any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("query timeout"));
        mockMvc.perform(get("/api/observe/customers/c-1/businesses")
                        .param("from", "2026-09-01").param("to", "2026-09-28"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000));
    }
}

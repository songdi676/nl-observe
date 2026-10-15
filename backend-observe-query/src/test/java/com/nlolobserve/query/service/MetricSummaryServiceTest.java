package com.nlolobserve.query.service;

import java.time.LocalDate;
import java.util.List;

import com.nlolobserve.common.dto.BizMetric;
import com.nlolobserve.common.dto.MetricSummary;
import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.query.config.QueryProperties;
import com.nlolobserve.query.repo.QueryRepository;
import com.nlolobserve.query.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricSummaryServiceTest {

    private final QueryRepository repository = mock(QueryRepository.class);
    private final QueryProperties properties = new QueryProperties();

    private MetricSummaryService service(int limitPerMin) {
        properties.setOpsViewRateLimitPerMin(limitPerMin);
        return new MetricSummaryService(repository, new OpsViewRateLimiter(properties));
    }

    @Test
    void summary_withCustomerId_noRateLimit() {
        when(repository.findFirstScreenLoadP95(any(), any(), eq("c-1"))).thenReturn(1830.5);
        BizMetric metric = new BizMetric();
        metric.setBizCode("ORDER");
        when(repository.findPerBizMetrics(any(), any(), any(), eq("c-1"))).thenReturn(List.of(metric));

        MetricSummary summary = service(0).summary("2026-09-01", "2026-09-28", null, "c-1");
        assertThat(summary.getFirstScreenLoadP95()).isEqualTo(1830.5);
        assertThat(summary.getPerBiz()).containsExactly(metric);
    }

    @Test
    void summary_opsView_overLimit_rateLimited() {
        MetricSummaryService svc = service(1);
        when(repository.findFirstScreenLoadP95(any(), any(), any())).thenReturn(1830.5);
        when(repository.findPerBizMetrics(any(), any(), any(), any())).thenReturn(List.of());

        svc.summary("2026-09-01", "2026-09-28", null, null); // 占用窗口内唯一配额（结果不校验）
        assertThatThrownBy(() -> svc.summary("2026-09-01", "2026-09-28", null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void summary_noData_notFound() {
        when(repository.findFirstScreenLoadP95(any(), any(), any())).thenReturn(null);
        when(repository.findPerBizMetrics(any(), any(), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service(60).summary("2026-09-01", "2026-09-28", null, "c-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void summary_invalidRange_badRequest() {
        assertThatThrownBy(() -> service(60).summary("2026-09-01", null, null, "c-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

}

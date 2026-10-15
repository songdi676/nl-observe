package com.nlolobserve.query.service;

import java.util.List;

import com.nlolobserve.common.dto.BizMetric;
import com.nlolobserve.common.dto.MetricSummary;
import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.query.repo.QueryRepository;
import com.nlolobserve.query.web.ApiException;
import org.springframework.stereotype.Service;

/**
 * 指标汇总查询（详设 §2.4）：firstScreenLoadP95 + perBiz（P95/成功率/计数）。
 * customerId 缺省 = 跨客户运维视图，走 {@link OpsViewRateLimiter} 限流桶（超限 42901）。
 */
@Service
public class MetricSummaryService {

    private final QueryRepository repository;
    private final OpsViewRateLimiter rateLimiter;

    public MetricSummaryService(QueryRepository repository, OpsViewRateLimiter rateLimiter) {
        this.repository = repository;
        this.rateLimiter = rateLimiter;
    }

    public MetricSummary summary(String from, String to, String bizCode, String customerId) {
        QueryRange range = QueryRange.parse(from, to);
        boolean opsView = customerId == null || customerId.isBlank();
        if (opsView && !rateLimiter.tryAcquire()) {
            throw new ApiException(ErrorCode.RATE_LIMITED);
        }
        Double firstScreenLoadP95 = repository.findFirstScreenLoadP95(range.from(), range.to(), customerId);
        List<BizMetric> perBiz = repository.findPerBizMetrics(range.from(), range.to(), customerId, bizCode);
        if (firstScreenLoadP95 == null && perBiz.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        MetricSummary summary = new MetricSummary();
        summary.setFirstScreenLoadP95(firstScreenLoadP95);
        summary.setPerBiz(perBiz);
        return summary;
    }
}

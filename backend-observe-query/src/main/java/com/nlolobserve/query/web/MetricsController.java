package com.nlolobserve.query.web;

import com.nlolobserve.common.dto.MetricSummary;
import com.nlolobserve.common.web.ApiResult;
import com.nlolobserve.query.service.MetricSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指标汇总 API（详设 §2.4）：GET /api/observe/metrics/summary。
 * from/to 必填，bizCode 可选，customerId 可选（缺省 = 跨客户运维视图，限流桶 60 次/分）。
 */
@RestController
@RequestMapping("/api/observe")
public class MetricsController {

    private final MetricSummaryService service;

    public MetricsController(MetricSummaryService service) {
        this.service = service;
    }

    @GetMapping("/metrics/summary")
    public ApiResult<MetricSummary> summary(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String bizCode,
            @RequestParam(required = false) String customerId) {
        return ApiResult.ok(service.summary(from, to, bizCode, customerId));
    }
}

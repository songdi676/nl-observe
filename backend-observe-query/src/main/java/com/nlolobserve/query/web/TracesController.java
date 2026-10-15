package com.nlolobserve.query.web;

import com.nlolobserve.common.dto.SpanDetail;
import com.nlolobserve.common.dto.SpanTree;
import com.nlolobserve.common.web.ApiResult;
import com.nlolobserve.query.service.SpanTreeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * trace/span API（详设 §2.2/§2.3）：
 * GET /api/observe/customers/{customerId}/traces/{traceId}
 * GET /api/observe/customers/{customerId}/traces/{traceId}/spans/{spanId}
 */
@RestController
@RequestMapping("/api/observe")
public class TracesController {

    private final SpanTreeService service;

    public TracesController(SpanTreeService service) {
        this.service = service;
    }

    @GetMapping("/customers/{customerId}/traces/{traceId}")
    public ApiResult<SpanTree> trace(@PathVariable String customerId, @PathVariable String traceId) {
        return ApiResult.ok(service.traceSpans(customerId, traceId));
    }

    @GetMapping("/customers/{customerId}/traces/{traceId}/spans/{spanId}")
    public ApiResult<SpanDetail> span(@PathVariable String customerId,
            @PathVariable String traceId, @PathVariable String spanId) {
        return ApiResult.ok(service.spanDetail(customerId, traceId, spanId));
    }
}

package com.nlolobserve.query.service;

import java.util.List;

import com.nlolobserve.common.dto.SpanDetail;
import com.nlolobserve.common.dto.SpanNode;
import com.nlolobserve.common.dto.SpanTree;
import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.query.repo.QueryRepository;
import com.nlolobserve.query.web.ApiException;
import org.springframework.stereotype.Service;

/**
 * trace/span 查询（详设 §2.2/§2.3）：只读 FINAL 查询；无数据 40401。
 * 响应为带 parentSpanId 的平铺 span 列表（前端按 parentSpanId 建树，详设 §2.2 形态）。
 */
@Service
public class SpanTreeService {

    private final QueryRepository repository;

    public SpanTreeService(QueryRepository repository) {
        this.repository = repository;
    }

    public SpanTree traceSpans(String customerId, String traceId) {
        if (customerId == null || customerId.isBlank() || traceId == null || traceId.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "customerId/traceId 必填");
        }
        List<SpanNode> spans = repository.findSpans(customerId, traceId);
        if (spans.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        SpanTree tree = new SpanTree();
        tree.setTraceId(traceId);
        tree.setSpans(spans);
        return tree;
    }

    public SpanDetail spanDetail(String customerId, String traceId, String spanId) {
        if (customerId == null || customerId.isBlank()
                || traceId == null || traceId.isBlank()
                || spanId == null || spanId.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "customerId/traceId/spanId 必填");
        }
        return repository.findSpan(customerId, traceId, spanId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}

package com.nlolobserve.common.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * span 树响应（详细设计 §2.2 的 data：{traceId, spans[]}）。
 */
public class SpanTree {

    private String traceId;
    private List<SpanNode> spans = new ArrayList<>();

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public List<SpanNode> getSpans() {
        return spans;
    }

    public void setSpans(List<SpanNode> spans) {
        this.spans = spans;
    }
}

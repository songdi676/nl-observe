package com.nlolobserve.common.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标汇总响应（详细设计 §2.4 GET /metrics/summary 的 data）。
 */
public class MetricSummary {

    private Double firstScreenLoadP95;
    private List<BizMetric> perBiz = new ArrayList<>();

    public Double getFirstScreenLoadP95() {
        return firstScreenLoadP95;
    }

    public void setFirstScreenLoadP95(Double firstScreenLoadP95) {
        this.firstScreenLoadP95 = firstScreenLoadP95;
    }

    public List<BizMetric> getPerBiz() {
        return perBiz;
    }

    public void setPerBiz(List<BizMetric> perBiz) {
        this.perBiz = perBiz;
    }
}

package com.nlolobserve.common.dto;

/**
 * 单业务指标聚合（详细设计 §2.4 perBiz 元素，P95/成功率由 Databend 计算）。
 */
public class BizMetric {

    private String bizCode;
    private double effectiveDurationP95;
    private double successRate;
    private long count;

    public String getBizCode() {
        return bizCode;
    }

    public void setBizCode(String bizCode) {
        this.bizCode = bizCode;
    }

    public double getEffectiveDurationP95() {
        return effectiveDurationP95;
    }

    public void setEffectiveDurationP95(double effectiveDurationP95) {
        this.effectiveDurationP95 = effectiveDurationP95;
    }

    public double getSuccessRate() {
        return successRate;
    }

    public void setSuccessRate(double successRate) {
        this.successRate = successRate;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}

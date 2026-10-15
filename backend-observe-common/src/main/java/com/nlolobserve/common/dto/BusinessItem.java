package com.nlolobserve.common.dto;

/**
 * 业务列表条目（详细设计 §2.1 GET /customers/{customerId}/businesses 的 data 元素）。
 */
public class BusinessItem {

    private String bizId;
    private String bizCode;
    private String traceId;
    private String startTime;
    private double effectiveDurationMs;
    private String status;
    private String endReason;

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public String getBizCode() {
        return bizCode;
    }

    public void setBizCode(String bizCode) {
        this.bizCode = bizCode;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public double getEffectiveDurationMs() {
        return effectiveDurationMs;
    }

    public void setEffectiveDurationMs(double effectiveDurationMs) {
        this.effectiveDurationMs = effectiveDurationMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEndReason() {
        return endReason;
    }

    public void setEndReason(String endReason) {
        this.endReason = endReason;
    }
}

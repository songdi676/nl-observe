package com.nlolobserve.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OTLP/JSON AnyValue（common 模型共享，三载荷通用）。
 * uint64（intValue）在 OTLP/JSON 中为字符串，按 String 建模避免溢出。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnyValue {

    private String stringValue;
    private String intValue;
    private Double doubleValue;
    private Boolean boolValue;
    private String bytesValue;

    public String getStringValue() {
        return stringValue;
    }

    public void setStringValue(String stringValue) {
        this.stringValue = stringValue;
    }

    public String getIntValue() {
        return intValue;
    }

    public void setIntValue(String intValue) {
        this.intValue = intValue;
    }

    public Double getDoubleValue() {
        return doubleValue;
    }

    public void setDoubleValue(Double doubleValue) {
        this.doubleValue = doubleValue;
    }

    public Boolean getBoolValue() {
        return boolValue;
    }

    public void setBoolValue(Boolean boolValue) {
        this.boolValue = boolValue;
    }

    public String getBytesValue() {
        return bytesValue;
    }

    public void setBytesValue(String bytesValue) {
        this.bytesValue = bytesValue;
    }
}

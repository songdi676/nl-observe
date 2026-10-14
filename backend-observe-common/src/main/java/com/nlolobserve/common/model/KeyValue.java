package com.nlolobserve.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OTLP/JSON KeyValue（attribute 条目，三载荷通用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeyValue {

    private String key;
    private AnyValue value;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public AnyValue getValue() {
        return value;
    }

    public void setValue(AnyValue value) {
        this.value = value;
    }
}

package com.nlolobserve.common.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OTLP/JSON Resource（三载荷通用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Resource {

    private List<KeyValue> attributes = new ArrayList<>();
    private int droppedAttributesCount;

    public List<KeyValue> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<KeyValue> attributes) {
        this.attributes = attributes;
    }

    public int getDroppedAttributesCount() {
        return droppedAttributesCount;
    }

    public void setDroppedAttributesCount(int droppedAttributesCount) {
        this.droppedAttributesCount = droppedAttributesCount;
    }
}

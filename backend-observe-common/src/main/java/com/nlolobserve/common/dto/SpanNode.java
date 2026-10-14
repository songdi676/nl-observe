package com.nlolobserve.common.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * span 树节点（详细设计 §2.2 GET /customers/{customerId}/traces/{traceId} 的 spans 元素）。
 */
public class SpanNode {

    private String spanId;
    private String parentSpanId;
    private String name;
    private int kind;
    private String startTime;
    private String endTime;
    private int statusCode;
    private double durationMs;
    private double effectiveDurationMs;
    private Map<String, Object> attributes = new HashMap<>();
    private List<EventNode> events = new ArrayList<>();

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public void setParentSpanId(String parentSpanId) {
        this.parentSpanId = parentSpanId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getKind() {
        return kind;
    }

    public void setKind(int kind) {
        this.kind = kind;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public double getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(double durationMs) {
        this.durationMs = durationMs;
    }

    public double getEffectiveDurationMs() {
        return effectiveDurationMs;
    }

    public void setEffectiveDurationMs(double effectiveDurationMs) {
        this.effectiveDurationMs = effectiveDurationMs;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public List<EventNode> getEvents() {
        return events;
    }

    public void setEvents(List<EventNode> events) {
        this.events = events;
    }

    /** span event（biz.suspend_start / biz.resume，时间线标记用） */
    public static class EventNode {
        private String name;
        private String time;
        private Map<String, Object> attributes = new HashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, Object> attributes) {
            this.attributes = attributes;
        }
    }
}

package com.nlolobserve.common.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单 span 全字段详情（详细设计 §2.3：attributes_json/scope_json/resource_json 解析为对象）。
 */
public class SpanDetail {

    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String traceState;
    private String name;
    private int kind;
    private String startTime;
    private String endTime;
    private int statusCode;
    private String statusMessage;
    private double durationMs;
    private double effectiveDurationMs;
    private String bizCode;
    private String serviceName;
    private Map<String, Object> resource = new HashMap<>();
    private Map<String, Object> scope = new HashMap<>();
    private Map<String, Object> attributes = new HashMap<>();
    private List<SpanNode.EventNode> events = new ArrayList<>();

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

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

    public String getTraceState() {
        return traceState;
    }

    public void setTraceState(String traceState) {
        this.traceState = traceState;
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

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
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

    public String getBizCode() {
        return bizCode;
    }

    public void setBizCode(String bizCode) {
        this.bizCode = bizCode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Map<String, Object> getResource() {
        return resource;
    }

    public void setResource(Map<String, Object> resource) {
        this.resource = resource;
    }

    public Map<String, Object> getScope() {
        return scope;
    }

    public void setScope(Map<String, Object> scope) {
        this.scope = scope;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public List<SpanNode.EventNode> getEvents() {
        return events;
    }

    public void setEvents(List<SpanNode.EventNode> events) {
        this.events = events;
    }
}

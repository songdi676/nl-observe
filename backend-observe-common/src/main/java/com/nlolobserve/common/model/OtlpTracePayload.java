package com.nlolobserve.common.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ExportTraceServiceRequest（OTLP/JSON）反序列化模型（详细设计 §1.1 model/）。
 * uint64 字段（*UnixNano）按 String 建模；未知字段忽略以保前向兼容。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OtlpTracePayload {

    private List<ResourceSpans> resourceSpans = new ArrayList<>();

    public List<ResourceSpans> getResourceSpans() {
        return resourceSpans;
    }

    public void setResourceSpans(List<ResourceSpans> resourceSpans) {
        this.resourceSpans = resourceSpans;
    }

    /** resourceSpans 条目 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceSpans {
        private Resource resource = new Resource();
        private List<ScopeSpans> scopeSpans = new ArrayList<>();

        public Resource getResource() {
            return resource;
        }

        public void setResource(Resource resource) {
            this.resource = resource;
        }

        public List<ScopeSpans> getScopeSpans() {
            return scopeSpans;
        }

        public void setScopeSpans(List<ScopeSpans> scopeSpans) {
            this.scopeSpans = scopeSpans;
        }
    }

    /** scopeSpans 条目 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScopeSpans {
        private InstrumentationScope scope = new InstrumentationScope();
        private List<Span> spans = new ArrayList<>();

        public InstrumentationScope getScope() {
            return scope;
        }

        public void setScope(InstrumentationScope scope) {
            this.scope = scope;
        }

        public List<Span> getSpans() {
            return spans;
        }

        public void setSpans(List<Span> spans) {
            this.spans = spans;
        }
    }

    /** span（业务 root span / REST span 通用） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Span {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String traceState;
        private String name;
        private int kind;
        private String startTimeUnixNano;
        private String endTimeUnixNano;
        private List<KeyValue> attributes = new ArrayList<>();
        private List<SpanEvent> events = new ArrayList<>();
        private List<SpanLink> links = new ArrayList<>();
        private Status status;

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

        public String getStartTimeUnixNano() {
            return startTimeUnixNano;
        }

        public void setStartTimeUnixNano(String startTimeUnixNano) {
            this.startTimeUnixNano = startTimeUnixNano;
        }

        public String getEndTimeUnixNano() {
            return endTimeUnixNano;
        }

        public void setEndTimeUnixNano(String endTimeUnixNano) {
            this.endTimeUnixNano = endTimeUnixNano;
        }

        public List<KeyValue> getAttributes() {
            return attributes;
        }

        public void setAttributes(List<KeyValue> attributes) {
            this.attributes = attributes;
        }

        public List<SpanEvent> getEvents() {
            return events;
        }

        public void setEvents(List<SpanEvent> events) {
            this.events = events;
        }

        public List<SpanLink> getLinks() {
            return links;
        }

        public void setLinks(List<SpanLink> links) {
            this.links = links;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }
    }

    /** span event（biz.suspend_start / biz.resume） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpanEvent {
        private String timeUnixNano;
        private String name;
        private List<KeyValue> attributes = new ArrayList<>();
        private int droppedAttributesCount;

        public String getTimeUnixNano() {
            return timeUnixNano;
        }

        public void setTimeUnixNano(String timeUnixNano) {
            this.timeUnixNano = timeUnixNano;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

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

    /** span link */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpanLink {
        private String traceId;
        private String spanId;
        private String traceState;
        private List<KeyValue> attributes = new ArrayList<>();
        private int droppedAttributesCount;

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

        public String getTraceState() {
            return traceState;
        }

        public void setTraceState(String traceState) {
            this.traceState = traceState;
        }

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

    /** span status */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Status {
        private int code;
        private String message;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}

package com.nlolobserve.common.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ExportLogsServiceRequest（OTLP/JSON）反序列化模型（详细设计 §1.1 model/）。
 * logs 链路为接收端/存储端通用能力（预留业务系统日志），SDK 不主动发 logs。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OtlpLogPayload {

    private List<ResourceLogs> resourceLogs = new ArrayList<>();

    public List<ResourceLogs> getResourceLogs() {
        return resourceLogs;
    }

    public void setResourceLogs(List<ResourceLogs> resourceLogs) {
        this.resourceLogs = resourceLogs;
    }

    /** resourceLogs 条目 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceLogs {
        private Resource resource = new Resource();
        private List<ScopeLogs> scopeLogs = new ArrayList<>();

        public Resource getResource() {
            return resource;
        }

        public void setResource(Resource resource) {
            this.resource = resource;
        }

        public List<ScopeLogs> getScopeLogs() {
            return scopeLogs;
        }

        public void setScopeLogs(List<ScopeLogs> scopeLogs) {
            this.scopeLogs = scopeLogs;
        }
    }

    /** scopeLogs 条目 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScopeLogs {
        private InstrumentationScope scope = new InstrumentationScope();
        private List<LogRecord> logRecords = new ArrayList<>();

        public InstrumentationScope getScope() {
            return scope;
        }

        public void setScope(InstrumentationScope scope) {
            this.scope = scope;
        }

        public List<LogRecord> getLogRecords() {
            return logRecords;
        }

        public void setLogRecords(List<LogRecord> logRecords) {
            this.logRecords = logRecords;
        }
    }

    /** log record */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LogRecord {
        private String timeUnixNano;
        private String observedTimeUnixNano;
        private int severityNumber;
        private String severityText;
        private AnyValue body;
        private List<KeyValue> attributes = new ArrayList<>();
        private String traceId;
        private String spanId;
        private String traceState;
        private int droppedAttributesCount;

        public String getTimeUnixNano() {
            return timeUnixNano;
        }

        public void setTimeUnixNano(String timeUnixNano) {
            this.timeUnixNano = timeUnixNano;
        }

        public String getObservedTimeUnixNano() {
            return observedTimeUnixNano;
        }

        public void setObservedTimeUnixNano(String observedTimeUnixNano) {
            this.observedTimeUnixNano = observedTimeUnixNano;
        }

        public int getSeverityNumber() {
            return severityNumber;
        }

        public void setSeverityNumber(int severityNumber) {
            this.severityNumber = severityNumber;
        }

        public String getSeverityText() {
            return severityText;
        }

        public void setSeverityText(String severityText) {
            this.severityText = severityText;
        }

        public AnyValue getBody() {
            return body;
        }

        public void setBody(AnyValue body) {
            this.body = body;
        }

        public List<KeyValue> getAttributes() {
            return attributes;
        }

        public void setAttributes(List<KeyValue> attributes) {
            this.attributes = attributes;
        }

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

        public int getDroppedAttributesCount() {
            return droppedAttributesCount;
        }

        public void setDroppedAttributesCount(int droppedAttributesCount) {
            this.droppedAttributesCount = droppedAttributesCount;
        }
    }
}

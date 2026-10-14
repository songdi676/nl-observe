package com.nlolobserve.common.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ExportMetricsServiceRequest（OTLP/JSON）反序列化模型（详细设计 §1.1 model/）。
 * SDK 只上报原始 data point（gauge/sum），不做统计聚合（概要设计 决策 4）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OtlpMetricPayload {

    private List<ResourceMetrics> resourceMetrics = new ArrayList<>();

    public List<ResourceMetrics> getResourceMetrics() {
        return resourceMetrics;
    }

    public void setResourceMetrics(List<ResourceMetrics> resourceMetrics) {
        this.resourceMetrics = resourceMetrics;
    }

    /** resourceMetrics 条目 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceMetrics {
        private Resource resource = new Resource();
        private List<ScopeMetrics> scopeMetrics = new ArrayList<>();

        public Resource getResource() {
            return resource;
        }

        public void setResource(Resource resource) {
            this.resource = resource;
        }

        public List<ScopeMetrics> getScopeMetrics() {
            return scopeMetrics;
        }

        public void setScopeMetrics(List<ScopeMetrics> scopeMetrics) {
            this.scopeMetrics = scopeMetrics;
        }
    }

    /** scopeMetrics 条目 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScopeMetrics {
        private InstrumentationScope scope = new InstrumentationScope();
        private List<Metric> metrics = new ArrayList<>();

        public InstrumentationScope getScope() {
            return scope;
        }

        public void setScope(InstrumentationScope scope) {
            this.scope = scope;
        }

        public List<Metric> getMetrics() {
            return metrics;
        }

        public void setMetrics(List<Metric> metrics) {
            this.metrics = metrics;
        }
    }

    /** metric（name/unit + 一种数据形态） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metric {
        private String name;
        private String description;
        private String unit;
        private Gauge gauge;
        private Sum sum;
        private Histogram histogram;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public Gauge getGauge() {
            return gauge;
        }

        public void setGauge(Gauge gauge) {
            this.gauge = gauge;
        }

        public Sum getSum() {
            return sum;
        }

        public void setSum(Sum sum) {
            this.sum = sum;
        }

        public Histogram getHistogram() {
            return histogram;
        }

        public void setHistogram(Histogram histogram) {
            this.histogram = histogram;
        }
    }

    /** gauge 形态（nl.first_screen_load） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Gauge {
        private List<DataPoint> dataPoints = new ArrayList<>();

        public List<DataPoint> getDataPoints() {
            return dataPoints;
        }

        public void setDataPoints(List<DataPoint> dataPoints) {
            this.dataPoints = dataPoints;
        }
    }

    /** sum 形态（nl.business_duration / nl.sdk.flush） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sum {
        private List<DataPoint> dataPoints = new ArrayList<>();
        private int aggregationTemporality;
        @JsonProperty("isMonotonic")
        private boolean monotonic;

        public List<DataPoint> getDataPoints() {
            return dataPoints;
        }

        public void setDataPoints(List<DataPoint> dataPoints) {
            this.dataPoints = dataPoints;
        }

        public int getAggregationTemporality() {
            return aggregationTemporality;
        }

        public void setAggregationTemporality(int aggregationTemporality) {
            this.aggregationTemporality = aggregationTemporality;
        }

        public boolean isMonotonic() {
            return monotonic;
        }

        public void setMonotonic(boolean monotonic) {
            this.monotonic = monotonic;
        }
    }

    /** histogram 形态（预留，SDK 当前不产出） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Histogram {
        private List<DataPoint> dataPoints = new ArrayList<>();
        private int aggregationTemporality;

        public List<DataPoint> getDataPoints() {
            return dataPoints;
        }

        public void setDataPoints(List<DataPoint> dataPoints) {
            this.dataPoints = dataPoints;
        }

        public int getAggregationTemporality() {
            return aggregationTemporality;
        }

        public void setAggregationTemporality(int aggregationTemporality) {
            this.aggregationTemporality = aggregationTemporality;
        }
    }

    /** data point（asDouble/asInt 二选一） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataPoint {
        private List<KeyValue> attributes = new ArrayList<>();
        private String startTimeUnixNano;
        private String timeUnixNano;
        private Double asDouble;
        private String asInt;

        public List<KeyValue> getAttributes() {
            return attributes;
        }

        public void setAttributes(List<KeyValue> attributes) {
            this.attributes = attributes;
        }

        public String getStartTimeUnixNano() {
            return startTimeUnixNano;
        }

        public void setStartTimeUnixNano(String startTimeUnixNano) {
            this.startTimeUnixNano = startTimeUnixNano;
        }

        public String getTimeUnixNano() {
            return timeUnixNano;
        }

        public void setTimeUnixNano(String timeUnixNano) {
            this.timeUnixNano = timeUnixNano;
        }

        public Double getAsDouble() {
            return asDouble;
        }

        public void setAsDouble(Double asDouble) {
            this.asDouble = asDouble;
        }

        public String getAsInt() {
            return asInt;
        }

        public void setAsInt(String asInt) {
            this.asInt = asInt;
        }
    }
}

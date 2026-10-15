package com.nlolobserve.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OTLP/JSON InstrumentationScope（三载荷通用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstrumentationScope {

    private String name;
    private String version;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}

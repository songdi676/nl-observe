package com.nlolobserve.storage.config;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.databend.* —— Databend 连接配置（详设 §1.3/§5，凭证走环境变量占位）。
 * 启动自检：url 必须含 presigned_url_disabled=true（NFR-04），缺失即启动失败（fail-fast）；
 * SELECT 1 连通性自检与 JDBC 数据源接入为 T08。
 */
@ConfigurationProperties(prefix = "observe.databend")
public class DatabendProperties {

    private String url = "";

    private String username = "";

    private String password = "";

    @PostConstruct
    public void validate() {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("observe.databend.url 未配置（凭证走环境变量 DATABEND_URL）");
        }
        if (!url.contains("presigned_url_disabled=true")) {
            throw new IllegalStateException(
                    "observe.databend.url 必须包含 presigned_url_disabled=true（Databend JDBC 约束，NFR-04），实际: " + url);
        }
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}

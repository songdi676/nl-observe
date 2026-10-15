package com.nlolobserve.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.databend.* —— Databend 只读连接配置（详设 §1.4/§5）。
 * 凭证一律环境变量占位（DATABEND_URL_RO/DATABEND_USER_RO/DATABEND_PASSWORD_RO），代码与配置不落口令。
 * 只读账号与存储端写账号（T08）分离：账号级只读权限由 DBA 侧 GRANT READ 保证，
 * 本类自检 presigned_url_disabled=true（同存储端口径，防预签名 URL 泄露查询载荷）。
 */
@ConfigurationProperties(prefix = "observe.databend")
public class DatabendProperties {

    /** url 必须包含的自检参数（详设 §1.3/§1.4：同存储端 presigned_url_disabled 自检） */
    public static final String REQUIRED_URL_PARAM = "presigned_url_disabled=true";

    private String url = "";
    private String username = "";
    private String password = "";
    /** 启动连接自检开关（SELECT 1）；集成测试置 false 避免依赖真实 Databend */
    private boolean checkOnStartup = true;

    /**
     * 启动自检：url 必须已配置且含 presigned_url_disabled=true，否则 fail-fast。
     */
    public void validate() {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "observe.databend.url 未配置（只读账号环境变量 DATABEND_URL_RO）");
        }
        if (!url.contains(REQUIRED_URL_PARAM)) {
            throw new IllegalStateException(
                    "observe.databend.url 必须包含 " + REQUIRED_URL_PARAM + "（同存储端 presigned_url_disabled 自检）");
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

    public boolean isCheckOnStartup() {
        return checkOnStartup;
    }

    public void setCheckOnStartup(boolean checkOnStartup) {
        this.checkOnStartup = checkOnStartup;
    }
}

package com.nlolobserve.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * observe.databend.* —— Databend 写连接配置（详设 §1.3/§5，凭证走环境变量占位 DATABEND_URL 等）。
 * 启动自检：url 必须含 presigned_url_disabled=true（NFR-04），缺失即启动失败（fail-fast）。
 * T08 评审 backport（T09 口径）：参数精确匹配（子串 contains 过宽，=trueX 值拼接也能通过），
 * 错误信息不回显连接串（含凭证/host）；validate() 由 {@link DatabendStartupCheck} 调用
 * （不再 @PostConstruct：自检时序统一在 ApplicationRunner，check-on-startup 开关只豁免 SELECT 1）。
 */
@ConfigurationProperties(prefix = "observe.databend")
public class DatabendProperties {

    /** url 必须包含的自检参数（详设 §1.3：防预签名 URL 泄露遥测载荷，NFR-04） */
    public static final String REQUIRED_URL_PARAM = "presigned_url_disabled=true";

    private String url = "";
    private String username = "";
    private String password = "";
    /** 启动连接自检开关（SELECT 1）；集成测试置 false 避免依赖真实 Databend。presigned 校验不豁免 */
    private boolean checkOnStartup = true;

    /**
     * 启动自检：url 必须已配置且含 presigned_url_disabled=true，否则 fail-fast。
     * 参数精确匹配：按 & 切分 query 段，整段等于 presigned_url_disabled=true 才算命中。
     */
    public void validate() {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("observe.databend.url 未配置（凭证走环境变量 DATABEND_URL）");
        }
        if (!hasRequiredParam(url)) {
            throw new IllegalStateException(
                    "observe.databend.url 必须包含 " + REQUIRED_URL_PARAM + "（Databend JDBC 约束，NFR-04）");
        }
    }

    static boolean hasRequiredParam(String url) {
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return false;
        }
        for (String segment : url.substring(q + 1).split("&")) {
            if (REQUIRED_URL_PARAM.equals(segment)) {
                return true;
            }
        }
        return false;
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

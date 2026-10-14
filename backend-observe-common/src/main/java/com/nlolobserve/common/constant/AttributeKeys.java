package com.nlolobserve.common.constant;

import java.util.List;

/**
 * Trace/Metric 属性键白名单（概要设计 §7、详细设计 §1.1）。
 * 仅 nl.* / biz.* / http.* 前缀允许进入 attributes；禁止手机号/证件号类 PII。
 */
public final class AttributeKeys {

    /** 业务系统客户编号（假名引用） */
    public static final String NL_CUSTOMER_ID = "nl.customer_id";
    /** REST span 耗时（毫秒） */
    public static final String NL_DURATION_MS = "nl.duration_ms";
    /** 指标：首屏加载（ms，每页一次） */
    public static final String NL_FIRST_SCREEN_LOAD = "nl.first_screen_load";
    /** 指标：业务有效耗时（ms，每业务一次） */
    public static final String NL_BUSINESS_DURATION = "nl.business_duration";
    /** 指标：flush 结果（best-effort） */
    public static final String NL_SDK_FLUSH = "nl.sdk.flush";

    /** 业务状态：success|fail */
    public static final String BIZ_STATUS = "biz.status";
    /** 结束原因：explicit|unmount */
    public static final String BIZ_END_REASON = "biz.end_reason";
    /** 累计挂起时长（ms） */
    public static final String BIZ_TOTAL_SUSPENDED_MS = "biz.total_suspended_ms";
    /** 有效耗时（ms）= 墙钟 - Σ挂起 */
    public static final String BIZ_EFFECTIVE_DURATION_MS = "biz.effective_duration_ms";
    /** 业务编码（指标 data point 属性） */
    public static final String BIZ_CODE = "biz.code";
    /** span event：挂起起点 */
    public static final String EVENT_BIZ_SUSPEND_START = "biz.suspend_start";
    /** span event：恢复 */
    public static final String EVENT_BIZ_RESUME = "biz.resume";
    /** 业务 root span 名称前缀：biz.business.&lt;bizCode&gt; */
    public static final String BIZ_BUSINESS_NAME_PREFIX = "biz.business.";

    /** REST 请求方法 */
    public static final String HTTP_REQUEST_METHOD = "http.request.method";
    /** 规范化路由（去 query） */
    public static final String HTTP_ROUTE = "http.route";
    /** HTTP 响应状态码 */
    public static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";

    /** REST span 属性白名单（AC-04：属性仅含以下 4 项） */
    public static final List<String> REST_SPAN_WHITELIST = List.of(
            HTTP_REQUEST_METHOD, HTTP_ROUTE, HTTP_RESPONSE_STATUS_CODE, NL_DURATION_MS);

    /** 业务 end 时写入的根 span 属性（详细设计 §7） */
    public static final List<String> BIZ_END_ATTRIBUTES = List.of(
            BIZ_TOTAL_SUSPENDED_MS, BIZ_EFFECTIVE_DURATION_MS, BIZ_STATUS, BIZ_END_REASON);

    private AttributeKeys() {
    }
}

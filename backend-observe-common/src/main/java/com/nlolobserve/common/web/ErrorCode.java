package com.nlolobserve.common.web;

/**
 * 统一错误码（详细设计 §4.1）：code 与 HTTP 状态一一对应。
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, 200, "ok"),
    /** 参数缺失/格式错误（from/to 必填、日期跨度>31天） */
    BAD_REQUEST(40001, 400, "参数缺失或格式错误"),
    /** IP 白名单拒绝（接收端） */
    IP_FORBIDDEN(40301, 403, "IP 白名单拒绝"),
    /** trace/span/客户无数据 */
    NOT_FOUND(40401, 404, "无数据"),
    /** 请求体超限（接收端） */
    PAYLOAD_TOO_LARGE(41301, 413, "请求体超限"),
    /** 限流（接收端/运维视图） */
    RATE_LIMITED(42901, 429, "触发限流"),
    /** 服务端内部错误 */
    INTERNAL_ERROR(50000, 500, "服务端内部错误");

    private final int code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    /** 按业务码反查（查询端/接收端异常映射用） */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知错误码: " + code);
    }
}

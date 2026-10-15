package com.nlolobserve.query.web;

import com.nlolobserve.common.web.ErrorCode;

/**
 * 查询端 API 业务异常：携带 {@link ErrorCode}，由 {@link GlobalExceptionHandler}
 * 统一映射为 {code,message,data} 响应与对应 HTTP 状态（详设 §4.1）。
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

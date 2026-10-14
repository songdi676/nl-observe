package com.nlolobserve.common.web;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 统一响应结构 {code, message, data}（详细设计 §2/§4.1，AC-08）。
 *
 * @param <T> data 载荷类型
 */
public class ApiResult<T> {

    private int code;
    private String message;
    private T data;

    public ApiResult() {
    }

    private ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getDefaultMessage(), data);
    }

    public static <T> ApiResult<T> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode) {
        return new ApiResult<>(errorCode.getCode(), errorCode.getDefaultMessage(), null);
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode, String message) {
        return new ApiResult<>(errorCode.getCode(), message, null);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }

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

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}

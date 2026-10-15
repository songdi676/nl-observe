package com.nlolobserve.query.web;

import com.nlolobserve.common.web.ApiResult;
import com.nlolobserve.common.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 查询端统一异常 → {code,message,data} 映射（详设 §4.1/§4.2）：
 * ApiException 按携带错误码；Databend 访问异常（含查询超时）→ 50000/500；其余兜底 50000/500。
 * 查询端只读无副作用（详设 §4.3），异常不触发任何写操作。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResult<Void>> handleApiException(ApiException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        String message = exception.getMessage() == null
                ? errorCode.getDefaultMessage() : exception.getMessage();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResult.fail(errorCode, message));
    }

    /** 未映射路径（含 /api/observe/** 之外的请求）→ 404（Newman 404 用例口径，详设 §6.3） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException exception) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus())
                .body(ApiResult.fail(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResult<Void>> handleDataAccess(DataAccessException exception) {
        LOG.error("查询 Databend 失败（含查询超时，observe.query-timeout-ms 约束）", exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResult.fail(ErrorCode.INTERNAL_ERROR));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception exception) {
        LOG.error("查询服务内部错误", exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResult.fail(ErrorCode.INTERNAL_ERROR));
    }
}

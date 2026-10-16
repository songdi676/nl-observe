package com.nlolobserve.receiver.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.web.ApiResult;

/**
 * 拒绝响应统一 JSON 序列化（T06 评审 Minor：OtlpSecurityFilter 与 IpWhitelistFilter 两处
 * 各自内联的 try/catch+手工兜底 JSON 收敛为一份；ApiResult 为纯 POJO，序列化失败不现实，
 * 兜底手工拼接仅保证状态码/错误码语义不丢）。
 */
final class ApiResultJson {

    private ApiResultJson() {
    }

    static String toJson(ApiResult<Void> result, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"code\":" + result.getCode() + ",\"message\":\""
                    + result.getMessage() + "\",\"data\":null}";
        }
    }
}

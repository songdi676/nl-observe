package com.nlolobserve.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * T02 测试用例：ApiResult 序列化结构 {code,message,data}；ErrorCode 七枚码值与 HTTP 映射。
 */
class ApiResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void okSerializesToCodeMessageData() throws Exception {
        ApiResult<List<String>> result = ApiResult.ok(List.of("a", "b"));
        JsonNode node = mapper.readTree(mapper.writeValueAsString(result));

        assertEquals(3, sizeOfTopLevelFields(node));
        assertEquals(0, node.get("code").asInt());
        assertEquals("ok", node.get("message").asText());
        assertTrue(node.get("data").isArray());
        assertEquals("a", node.get("data").get(0).asText());
    }

    @Test
    void okWithoutDataKeepsStructure() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(ApiResult.ok()));
        assertEquals(0, node.get("code").asInt());
        assertEquals("ok", node.get("message").asText());
        assertTrue(node.has("data"));
        assertTrue(node.get("data").isNull());
    }

    @Test
    void failCarriesErrorCodeAndDefaultMessage() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(ApiResult.fail(ErrorCode.NOT_FOUND)));
        assertEquals(40401, node.get("code").asInt());
        assertFalse(node.get("message").asText().isEmpty());
        assertTrue(node.get("data").isNull());
    }

    @Test
    void failWithCustomMessageOverridesDefault() {
        ApiResult<Void> result = ApiResult.fail(ErrorCode.BAD_REQUEST, "from/to 必填");
        assertEquals(40001, result.getCode());
        assertEquals("from/to 必填", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void isSuccessAndFromCodeHelpers() {
        assertTrue(ApiResult.ok("x").isSuccess());
        assertFalse(ApiResult.fail(ErrorCode.INTERNAL_ERROR).isSuccess());
        assertEquals(ErrorCode.RATE_LIMITED, ErrorCode.fromCode(42901));
        assertEquals(ErrorCode.SUCCESS, ErrorCode.fromCode(0));
    }

    @Test
    void fromCodeRejectsUnknownCode() {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ErrorCode.fromCode(99999));
        assertTrue(ex.getMessage().contains("99999"));
    }

    @Test
    void errorCodeTableMatchesDesignSection41() {
        assertEquals(0, ErrorCode.SUCCESS.getCode());
        assertEquals(200, ErrorCode.SUCCESS.getHttpStatus());
        assertEquals(40001, ErrorCode.BAD_REQUEST.getCode());
        assertEquals(400, ErrorCode.BAD_REQUEST.getHttpStatus());
        assertEquals(40301, ErrorCode.IP_FORBIDDEN.getCode());
        assertEquals(403, ErrorCode.IP_FORBIDDEN.getHttpStatus());
        assertEquals(40401, ErrorCode.NOT_FOUND.getCode());
        assertEquals(404, ErrorCode.NOT_FOUND.getHttpStatus());
        assertEquals(41301, ErrorCode.PAYLOAD_TOO_LARGE.getCode());
        assertEquals(413, ErrorCode.PAYLOAD_TOO_LARGE.getHttpStatus());
        assertEquals(42901, ErrorCode.RATE_LIMITED.getCode());
        assertEquals(429, ErrorCode.RATE_LIMITED.getHttpStatus());
        assertEquals(50000, ErrorCode.INTERNAL_ERROR.getCode());
        assertEquals(500, ErrorCode.INTERNAL_ERROR.getHttpStatus());
        assertEquals(7, ErrorCode.values().length);
    }

    private static int sizeOfTopLevelFields(JsonNode node) {
        int count = 0;
        var fields = node.fields();
        while (fields.hasNext()) {
            fields.next();
            count++;
        }
        return count;
    }
}

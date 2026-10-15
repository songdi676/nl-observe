package com.nlolobserve.common.constant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * T02 验收：AttributeKeys/Topics 常量覆盖 nl.* 、biz.* 、http.* 白名单与三 topic 名（概要设计 §5.3/§7）。
 */
class AttributeKeysTest {

    @Test
    void nlWhitelistKeys() {
        assertEquals("nl.customer_id", AttributeKeys.NL_CUSTOMER_ID);
        assertEquals("nl.duration_ms", AttributeKeys.NL_DURATION_MS);
        assertEquals("nl.first_screen_load", AttributeKeys.NL_FIRST_SCREEN_LOAD);
        assertEquals("nl.business_duration", AttributeKeys.NL_BUSINESS_DURATION);
        assertEquals("nl.sdk.flush", AttributeKeys.NL_SDK_FLUSH);
    }

    @Test
    void bizWhitelistKeys() {
        assertEquals("biz.status", AttributeKeys.BIZ_STATUS);
        assertEquals("biz.end_reason", AttributeKeys.BIZ_END_REASON);
        assertEquals("biz.total_suspended_ms", AttributeKeys.BIZ_TOTAL_SUSPENDED_MS);
        assertEquals("biz.effective_duration_ms", AttributeKeys.BIZ_EFFECTIVE_DURATION_MS);
        assertEquals("biz.code", AttributeKeys.BIZ_CODE);
        assertEquals("biz.suspend_start", AttributeKeys.EVENT_BIZ_SUSPEND_START);
        assertEquals("biz.resume", AttributeKeys.EVENT_BIZ_RESUME);
        assertEquals("biz.business.", AttributeKeys.BIZ_BUSINESS_NAME_PREFIX);
    }

    @Test
    void httpWhitelistKeys() {
        assertEquals("http.request.method", AttributeKeys.HTTP_REQUEST_METHOD);
        assertEquals("http.route", AttributeKeys.HTTP_ROUTE);
        assertEquals("http.response.status_code", AttributeKeys.HTTP_RESPONSE_STATUS_CODE);
    }

    @Test
    void restSpanAttributeWhitelistIsExactlyFourItems() {
        assertEquals(4, AttributeKeys.REST_SPAN_WHITELIST.size());
        assertTrue(AttributeKeys.REST_SPAN_WHITELIST.contains("http.request.method"));
        assertTrue(AttributeKeys.REST_SPAN_WHITELIST.contains("http.route"));
        assertTrue(AttributeKeys.REST_SPAN_WHITELIST.contains("http.response.status_code"));
        assertTrue(AttributeKeys.REST_SPAN_WHITELIST.contains("nl.duration_ms"));
    }

    @Test
    void bizEndAttributeWhitelist() {
        assertTrue(AttributeKeys.BIZ_END_ATTRIBUTES.contains("biz.total_suspended_ms"));
        assertTrue(AttributeKeys.BIZ_END_ATTRIBUTES.contains("biz.effective_duration_ms"));
        assertTrue(AttributeKeys.BIZ_END_ATTRIBUTES.contains("biz.status"));
        assertTrue(AttributeKeys.BIZ_END_ATTRIBUTES.contains("biz.end_reason"));
    }

    @Test
    void topicsMatchRequirementYaml() {
        assertEquals("nl-observe.otlp.traces", Topics.TRACES);
        assertEquals("nl-observe.otlp.metrics", Topics.METRICS);
        assertEquals("nl-observe.otlp.logs", Topics.LOGS);
        assertEquals(3, Topics.ALL.size());
        assertTrue(Topics.ALL.containsAll(java.util.List.of(
                "nl-observe.otlp.traces", "nl-observe.otlp.metrics", "nl-observe.otlp.logs")));
    }
}

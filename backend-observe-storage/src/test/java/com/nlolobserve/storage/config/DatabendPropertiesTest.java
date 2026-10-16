package com.nlolobserve.storage.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T07/T08：DatabendProperties 启动自检（详设 §1.3：url 必须含 presigned_url_disabled=true）。
 * T08 评审 backport（T09 口径）：参数精确匹配（防 =trueX 值拼接绕过），错误信息不回显连接串。
 * SELECT 1 连接自检见 DatabendStartupCheckTest。
 */
class DatabendPropertiesTest {

    @Test
    void urlWithPresignedUrlDisabled_passes() {
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://10.1.8.33:8000/observe?presigned_url_disabled=true");
        props.setUsername("writer");
        props.setPassword("secret");
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void urlWithParamAmongMultipleQueryParams_passes() {
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://10.1.8.33:8000/observe?x=1&presigned_url_disabled=true&y=2");
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void urlWithConcatenatedParamValue_fails_notSubstringMatch() {
        // T09 评审 m3 backport：参数值拼接（=trueX）不得被子串 contains 放过
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://10.1.8.33:8000/observe?x=1&presigned_url_disabled=trueX");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DatabendProperties.REQUIRED_URL_PARAM);
    }

    @Test
    void urlMissingPresignedUrlDisabled_failsFast() {
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://10.1.8.33:8000/observe");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("presigned_url_disabled=true");
    }

    @Test
    void blankUrl_failsFast() {
        DatabendProperties props = new DatabendProperties();
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("observe.databend.url");
    }

    @Test
    void errorMessage_doesNotEchoConnectionUrl() {
        // T07 评审 backport：错误信息不得回显连接串（含凭证参数）
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://10.1.8.33:8000/observe");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("presigned_url_disabled=true")
                .hasMessageNotContaining("10.1.8.33");
    }

    @Test
    void defaults_checkOnStartupTrue() {
        DatabendProperties props = new DatabendProperties();
        org.assertj.core.api.Assertions.assertThat(props.isCheckOnStartup()).isTrue();
    }
}

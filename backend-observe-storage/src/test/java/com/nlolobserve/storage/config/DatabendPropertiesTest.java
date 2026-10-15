package com.nlolobserve.storage.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T07：DatabendProperties 启动自检（详设 §1.3：url 必须含 presigned_url_disabled=true）。
 * SELECT 1 连接自检为 T08；本用例固化连接串参数断言。
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
}

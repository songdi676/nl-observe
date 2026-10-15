package com.nlolobserve.query.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T09 验收 #1：只读连接配置自检——url 必须含 presigned_url_disabled=true（同存储端口径）。
 */
class DatabendPropertiesTest {

    @Test
    void validate_acceptsUrlWithPresignedDisabled() {
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://10.1.8.33:8000/observe?presigned_url_disabled=true");
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsBlankUrl() {
        DatabendProperties props = new DatabendProperties();
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABEND_URL_RO");
    }

    @Test
    void validate_rejectsUrlWithoutPresignedDisabledParam() {
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://10.1.8.33:8000/observe");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DatabendProperties.REQUIRED_URL_PARAM);
    }

    @Test
    void defaults_checkOnStartupTrue() {
        DatabendProperties props = new DatabendProperties();
        org.assertj.core.api.Assertions.assertThat(props.isCheckOnStartup()).isTrue();
    }
}

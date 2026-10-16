package com.nlolobserve.storage.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T08 验收 #3（单元测试）：启动自检——presigned_url_disabled 校验不豁免（含精确匹配）；
 * SELECT 1 失败即启动失败（fail-fast）。
 */
class DatabendStartupCheckTest {

    private static DatabendProperties validProps(boolean checkOnStartup) {
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://localhost:8000/observe?presigned_url_disabled=true");
        props.setCheckOnStartup(checkOnStartup);
        return props;
    }

    @Test
    void run_selectOneSucceeds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        DatabendStartupCheck check = new DatabendStartupCheck(validProps(true), jdbc);
        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
        verify(jdbc).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void run_selectOneFails_startupFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new BadSqlGrammarException("select", "SELECT 1",
                        new java.sql.SQLException("auth failed")));
        DatabendStartupCheck check = new DatabendStartupCheck(validProps(true), jdbc);
        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自检失败");
    }

    @Test
    void run_checkDisabled_skipsConnectionButKeepsUrlValidation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DatabendStartupCheck check = new DatabendStartupCheck(validProps(false), jdbc);
        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
        verifyNoInteractions(jdbc);
    }

    @Test
    void run_invalidUrl_failsEvenWhenCheckDisabled() {
        DatabendProperties props = validProps(false);
        props.setUrl("jdbc:databend://localhost:8000/observe");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DatabendStartupCheck check = new DatabendStartupCheck(props, jdbc);
        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DatabendProperties.REQUIRED_URL_PARAM);
        verifyNoInteractions(jdbc);
    }
}

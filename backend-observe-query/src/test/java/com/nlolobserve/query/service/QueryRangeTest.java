package com.nlolobserve.query.service;

import java.time.LocalDate;

import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.query.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T09 参数校验（详设 §1.4）：from/to 必填、yyyy-MM-dd、from≤to、跨度 ≤31 天。
 */
class QueryRangeTest {

    @Test
    void parse_validRange() {
        QueryRange range = QueryRange.parse("2026-09-01", "2026-09-28");
        assertThat(range.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(range.to()).isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    void parse_exactly31DaysAccepted() {
        assertThat(QueryRange.parse("2026-09-01", "2026-10-01").from()).isNotNull();
    }

    @Test
    void parse_missingFromOrTo_badRequest() {
        assertThatThrownBy(() -> QueryRange.parse(null, "2026-09-28"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> QueryRange.parse("2026-09-01", "  "))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void parse_malformedDate_badRequest() {
        assertThatThrownBy(() -> QueryRange.parse("2026/09/01", "2026-09-28"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void parse_fromAfterTo_badRequest() {
        assertThatThrownBy(() -> QueryRange.parse("2026-09-28", "2026-09-01"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("from 不得晚于 to");
    }

    @Test
    void parse_spanOver31Days_badRequest() {
        assertThatThrownBy(() -> QueryRange.parse("2026-09-01", "2026-10-02"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("31");
    }
}

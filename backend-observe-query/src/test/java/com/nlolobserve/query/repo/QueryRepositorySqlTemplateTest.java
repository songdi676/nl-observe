package com.nlolobserve.query.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T09 测试用例：QueryRepository SQL 模板断言（命名参数占位）。
 * 安全评审 A03：全部过滤条件经 :named 占位符绑定，模板内不得出现值拼接/格式化。
 */
class QueryRepositorySqlTemplateTest {

    @Test
    void businessItemsTemplate_usesNamedParamsAndFinal() {
        assertThat(QueryRepository.SQL_BUSINESS_ITEMS)
                .contains(":customerId")
                .contains(":bizNamePrefix")
                .contains(":fromInclusive")
                .contains(":toExclusive")
                .contains(":bizCode")
                .contains("FINAL")
                .contains("LIMIT " + QueryRepository.BUSINESS_LIST_LIMIT);
        assertNoValueInterpolation(QueryRepository.SQL_BUSINESS_ITEMS);
    }

    @Test
    void spansByTraceTemplate_usesNamedParamsAndFinal() {
        assertThat(QueryRepository.SQL_SPANS_BY_TRACE)
                .contains(":customerId")
                .contains(":traceId")
                .contains("FINAL");
        assertNoValueInterpolation(QueryRepository.SQL_SPANS_BY_TRACE);
    }

    @Test
    void spanDetailTemplate_usesNamedParamsAndFinal() {
        assertThat(QueryRepository.SQL_SPAN_DETAIL)
                .contains(":customerId")
                .contains(":traceId")
                .contains(":spanId")
                .contains("FINAL");
        assertNoValueInterpolation(QueryRepository.SQL_SPAN_DETAIL);
    }

    @Test
    void firstScreenP95Template_usesNamedParamsAndFinal() {
        assertThat(QueryRepository.SQL_FIRST_SCREEN_LOAD_P95)
                .contains(":metricName")
                .contains(":fromInclusive")
                .contains(":toExclusive")
                .contains(":customerId")
                .contains("FINAL");
        assertNoValueInterpolation(QueryRepository.SQL_FIRST_SCREEN_LOAD_P95);
    }

    @Test
    void perBizSummaryTemplate_usesNamedParamsAndFinal() {
        assertThat(QueryRepository.SQL_PER_BIZ_SUMMARY)
                .contains(":bizNamePrefix")
                .contains(":fromInclusive")
                .contains(":toExclusive")
                .contains(":customerId")
                .contains(":bizCode")
                .contains("FINAL")
                .contains("GROUP BY");
        assertNoValueInterpolation(QueryRepository.SQL_PER_BIZ_SUMMARY);
    }

    /** 禁止格式化占位/字符串值注入痕迹（%s、{} 等运行时拼接入口） */
    private static void assertNoValueInterpolation(String sql) {
        assertThat(sql)
                .doesNotContain("%s")
                .doesNotContain("{}")
                .doesNotContain("${");
    }
}

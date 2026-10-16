package com.nlolobserve.storage.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.storage.parse.OtlpFixtures;
import com.nlolobserve.storage.parse.OtlpJsonParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * T08 验收 #2（单元测试）：otel_spans JDBC 命名参数 batch 写入（500/批），无字符串拼接。
 * SQL 为静态 REPLACE INTO 模板（去重身份=聚簇键，详设 §3/§4.3），值全部经命名参数绑定。
 */
class JdbcSpansRepositoryTest {

    private static final int BATCH_SIZE = 500;

    private final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcSpansRepository repository =
            new JdbcSpansRepository(namedJdbc, new OtlpJsonParser(new ObjectMapper()));

    @Test
    void write_usesReplaceIntoTemplateWithNamedParameters() {
        when(namedJdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{1});
        repository.write(List.of(OtlpFixtures.sampleSpans()));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource[]> sourcesCaptor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(namedJdbc).batchUpdate(sqlCaptor.capture(), sourcesCaptor.capture());

        // 静态模板：REPLACE INTO + 命名参数占位；值不得以字面量拼入 SQL 文本
        assertThat(sqlCaptor.getValue())
                .startsWith("REPLACE INTO otel_spans")
                .contains(":customerId")
                .contains(":traceId")
                .contains(":spanId")
                .doesNotContain(OtlpFixtures.TRACE_ID)
                .doesNotContain("c-1");

        MapSqlParameterSource params = (MapSqlParameterSource) sourcesCaptor.getValue()[0];
        assertThat(params.getValue("customerId")).isEqualTo("c-1");
        assertThat(params.getValue("traceId")).isEqualTo(OtlpFixtures.TRACE_ID);
        assertThat(params.getValue("spanId")).isEqualTo(OtlpFixtures.SPAN_ID);
        assertThat(params.getValue("bizCode")).isEqualTo("ORDER");
        assertThat(params.getValue("effectiveDurationMs")).isEqualTo(250.0);
        assertThat(params.getValue("startTime"))
                .isEqualTo(Timestamp.from(Instant.ofEpochSecond(1_760_512_800)));
        assertThat(params.getValue("endTime"))
                .isEqualTo(Timestamp.from(Instant.ofEpochSecond(1_760_512_803)));
    }

    @Test
    void write_overBatchSize_splitsIntoBatchesOf500() {
        when(namedJdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[0]);
        List<String> payloads = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE + 100; i++) {
            payloads.add(OtlpFixtures.spanEnvelope("0000000000000000000000000000000" + i % 10,
                    "00000000000000" + i, "s", "[]"));
        }

        repository.write(payloads);

        ArgumentCaptor<SqlParameterSource[]> captor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(namedJdbc, times(2)).batchUpdate(anyString(), captor.capture());
        assertThat(captor.getAllValues().get(0)).hasSize(BATCH_SIZE);
        assertThat(captor.getAllValues().get(1)).hasSize(100);
    }

    @Test
    void write_emptyPayloads_noBatchUpdate() {
        repository.write(List.of());
        verify(namedJdbc, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
    }
}

package com.nlolobserve.storage.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
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
 * T08 验收 #2（单元测试）：otel_logs JDBC 命名参数 batch 写入——row_hash 作为聚簇键分量绑定。
 */
class JdbcLogsRepositoryTest {

    private final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcLogsRepository repository =
            new JdbcLogsRepository(namedJdbc, new OtlpJsonParser(new ObjectMapper()));

    @Test
    void write_usesReplaceIntoTemplateWithNamedParameters_bindsRowHash() {
        when(namedJdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{1});
        repository.write(List.of(OtlpFixtures.sampleLogs()));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource[]> sourcesCaptor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(namedJdbc).batchUpdate(sqlCaptor.capture(), sourcesCaptor.capture());

        assertThat(sqlCaptor.getValue())
                .startsWith("REPLACE INTO otel_logs")
                .contains(":rowHash")
                .contains(":severityNumber")
                .doesNotContain("user logged in");

        MapSqlParameterSource params = (MapSqlParameterSource) sourcesCaptor.getValue()[0];
        assertThat(params.getValue("customerId")).isEqualTo("c-1");
        assertThat(params.getValue("traceId")).isEqualTo(OtlpFixtures.TRACE_ID);
        assertThat(params.getValue("spanId")).isEqualTo(OtlpFixtures.SPAN_ID);
        assertThat(params.getValue("severityNumber")).isEqualTo(9);
        assertThat(params.getValue("severityText")).isEqualTo("INFO");
        assertThat(params.getValue("body")).isEqualTo("user logged in");
        assertThat(params.getValue("time")).isEqualTo(Timestamp.from(Instant.ofEpochSecond(1_760_512_805)));
        assertThat(String.valueOf(params.getValue("rowHash"))).isNotBlank();
    }
}

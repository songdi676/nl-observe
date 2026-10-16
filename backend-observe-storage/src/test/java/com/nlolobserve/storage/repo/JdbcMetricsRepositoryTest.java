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
 * T08 验收 #2（单元测试）：otel_metrics JDBC 命名参数 batch 写入——dp_hash 作为聚簇键分量绑定。
 */
class JdbcMetricsRepositoryTest {

    private final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcMetricsRepository repository =
            new JdbcMetricsRepository(namedJdbc, new OtlpJsonParser(new ObjectMapper()));

    @Test
    void write_usesReplaceIntoTemplateWithNamedParameters_bindsDpHash() {
        when(namedJdbc.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[]{2});
        repository.write(List.of(OtlpFixtures.sampleMetrics()));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource[]> sourcesCaptor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(namedJdbc).batchUpdate(sqlCaptor.capture(), sourcesCaptor.capture());

        assertThat(sqlCaptor.getValue())
                .startsWith("REPLACE INTO otel_metrics")
                .contains(":dpHash")
                .contains(":metricName")
                .doesNotContain("nl.first_screen_load");

        SqlParameterSource[] sources = sourcesCaptor.getValue();
        assertThat(sources).hasSize(2);
        MapSqlParameterSource first = (MapSqlParameterSource) sources[0];
        assertThat(first.getValue("customerId")).isEqualTo("c-1");
        assertThat(first.getValue("metricName")).isEqualTo("nl.first_screen_load");
        assertThat(first.getValue("metricType")).isEqualTo(1);
        assertThat(first.getValue("value")).isEqualTo(1000.0);
        assertThat(first.getValue("time")).isEqualTo(Timestamp.from(Instant.ofEpochSecond(1_760_512_802)));
        assertThat(String.valueOf(first.getValue("dpHash"))).isNotBlank();

        MapSqlParameterSource second = (MapSqlParameterSource) sources[1];
        assertThat(second.getValue("metricName")).isEqualTo("nl.business_duration");
        assertThat(second.getValue("bizCode")).isEqualTo("ORDER");
        assertThat(second.getValue("dpHash")).isNotEqualTo(first.getValue("dpHash"));
    }
}

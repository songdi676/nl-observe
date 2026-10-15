package com.nlolobserve.query.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.common.dto.BizMetric;
import com.nlolobserve.common.dto.BusinessItem;
import com.nlolobserve.common.dto.SpanDetail;
import com.nlolobserve.common.dto.SpanNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T09 验收 #1（单测）：QueryRepository 全部经命名参数绑定（mock JDBC 断言 SQL 为模板常量、
 * 参数以具名值传入，无用户值拼进 SQL——同 §6.1 SpansRepository 断言口径）。
 */
class QueryRepositoryTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final QueryRepository repo = new QueryRepository(jdbc, new ObjectMapper());

    @Test
    void findBusinessItems_bindsNamedParams() {
        repo.findBusinessItems("c-1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).isEqualTo(QueryRepository.SQL_BUSINESS_ITEMS);

        MapSqlParameterSource src = (MapSqlParameterSource) params.getValue();
        assertThat(src.getValue("customerId")).isEqualTo("c-1");
        assertThat(src.getValue("bizNamePrefix")).isEqualTo("biz.business.%");
        assertThat(src.getValue("bizCode")).isEqualTo("");
        assertThat((Timestamp) src.getValue("fromInclusive")).isEqualTo(
                Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
        assertThat((Timestamp) src.getValue("toExclusive")).isEqualTo(
                Timestamp.from(Instant.parse("2026-09-29T00:00:00Z")));
    }

    @Test
    void findBusinessItems_mapperReadsRow() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("span_id")).thenReturn("b-001");
        when(rs.getString("biz_code")).thenReturn("ORDER");
        when(rs.getString("trace_id")).thenReturn("4bf92f35");
        when(rs.getTimestamp("start_time")).thenReturn(Timestamp.from(Instant.parse("2026-09-28T09:00:00Z")));
        when(rs.getDouble("effective_duration_ms")).thenReturn(45210.0);
        when(rs.getString("biz_status")).thenReturn("success");
        when(rs.getString("biz_end_reason")).thenReturn("explicit");

        BusinessItem item = QueryRepository.BUSINESS_ITEM_MAPPER.mapRow(rs, 0);
        assertThat(item.getBizId()).isEqualTo("b-001");
        assertThat(item.getBizCode()).isEqualTo("ORDER");
        assertThat(item.getStartTime()).isEqualTo("2026-09-28T09:00:00Z");
        assertThat(item.getEffectiveDurationMs()).isEqualTo(45210.0);
        assertThat(item.getStatus()).isEqualTo("success");
        assertThat(item.getEndReason()).isEqualTo("explicit");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findSpans_parsesAttributesJson() throws SQLException {
        ResultSet rs = mockSpanRow();
        when(jdbc.query(eq(QueryRepository.SQL_SPANS_BY_TRACE), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> List.of(((RowMapper<SpanNode>) inv.getArgument(2)).mapRow(rs, 0)));

        List<SpanNode> nodes = repo.findSpans("c-1", "t-1");
        assertThat(nodes).hasSize(1);
        SpanNode node = nodes.get(0);
        assertThat(node.getSpanId()).isEqualTo("s1");
        assertThat(node.getParentSpanId()).isEmpty();
        assertThat(node.getName()).isEqualTo("biz.business.ORDER");
        assertThat(node.getDurationMs()).isEqualTo(45210.0);
        assertThat(node.getAttributes()).containsEntry("biz.status", "success");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findSpan_returnsDetailWithParsedJsonFields() throws SQLException {
        ResultSet rs = mockSpanRow();
        when(rs.getString("trace_state")).thenReturn("");
        when(rs.getString("status_message")).thenReturn("");
        when(rs.getString("service_name")).thenReturn("web-shop");
        when(rs.getString("resource_json")).thenReturn("{\"service.name\":\"web-shop\"}");
        when(rs.getString("scope_json")).thenReturn("");
        when(jdbc.query(eq(QueryRepository.SQL_SPAN_DETAIL), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> List.of(((RowMapper<SpanDetail>) inv.getArgument(2)).mapRow(rs, 0)));

        Optional<SpanDetail> detail = repo.findSpan("c-1", "t-1", "s1");
        assertThat(detail).isPresent();
        assertThat(detail.get().getResource()).containsEntry("service.name", "web-shop");
        assertThat(detail.get().getAttributes()).containsEntry("biz.status", "success");
        assertThat(detail.get().getScope()).isEmpty();
    }

    @Test
    void findSpan_emptyWhenNoRows() {
        when(jdbc.query(eq(QueryRepository.SQL_SPAN_DETAIL), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertThat(repo.findSpan("c-1", "t-1", "missing")).isEmpty();
    }

    @Test
    void findFirstScreenLoadP95_bindsMetricNameAndSentinel() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Double.class)))
                .thenReturn(List.of(1830.5));

        Double p95 = repo.findFirstScreenLoadP95(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), null);
        assertThat(p95).isEqualTo(1830.5);

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForList(eq(QueryRepository.SQL_FIRST_SCREEN_LOAD_P95), params.capture(), eq(Double.class));
        MapSqlParameterSource src = (MapSqlParameterSource) params.getValue();
        assertThat(src.getValue("metricName")).isEqualTo("nl.first_screen_load");
        assertThat(src.getValue("customerId")).isEqualTo("");
    }

    @Test
    void findFirstScreenLoadP95_nullWhenNoRows() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Double.class)))
                .thenReturn(List.of());
        assertThat(repo.findFirstScreenLoadP95(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), "c-1")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findPerBizMetrics_computesSuccessRate() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("biz_code")).thenReturn("ORDER");
        when(rs.getDouble("effective_duration_p95")).thenReturn(52100.0);
        when(rs.getLong("span_count")).thenReturn(552L);
        when(rs.getDouble("success_count")).thenReturn(542.0);
        when(jdbc.query(eq(QueryRepository.SQL_PER_BIZ_SUMMARY), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> List.of(((RowMapper<BizMetric>) inv.getArgument(2)).mapRow(rs, 0)));

        List<BizMetric> perBiz = repo.findPerBizMetrics(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), "ORDER", "c-1");
        assertThat(perBiz).hasSize(1);
        assertThat(perBiz.get(0).getBizCode()).isEqualTo("ORDER");
        assertThat(perBiz.get(0).getEffectiveDurationP95()).isEqualTo(52100.0);
        assertThat(perBiz.get(0).getCount()).isEqualTo(552L);
        assertThat(perBiz.get(0).getSuccessRate()).isCloseTo(542.0 / 552.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    private ResultSet mockSpanRow() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("span_id")).thenReturn("s1");
        when(rs.getString("parent_span_id")).thenReturn("");
        when(rs.getString("name")).thenReturn("biz.business.ORDER");
        when(rs.getInt("kind")).thenReturn(1);
        when(rs.getTimestamp("start_time")).thenReturn(Timestamp.from(Instant.parse("2026-09-28T09:00:00Z")));
        when(rs.getTimestamp("end_time")).thenReturn(Timestamp.from(Instant.parse("2026-09-28T09:00:45.210Z")));
        when(rs.getInt("status_code")).thenReturn(0);
        when(rs.getDouble("effective_duration_ms")).thenReturn(45210.0);
        when(rs.getString("attributes_json")).thenReturn("{\"biz.status\":\"success\"}");
        when(rs.getString("biz_code")).thenReturn("ORDER");
        return rs;
    }
}

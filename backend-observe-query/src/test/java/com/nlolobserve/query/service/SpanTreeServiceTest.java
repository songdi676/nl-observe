package com.nlolobserve.query.service;

import java.util.List;
import java.util.Optional;

import com.nlolobserve.common.dto.SpanDetail;
import com.nlolobserve.common.dto.SpanNode;
import com.nlolobserve.common.dto.SpanTree;
import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.query.repo.QueryRepository;
import com.nlolobserve.query.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpanTreeServiceTest {

    private final QueryRepository repository = mock(QueryRepository.class);
    private final SpanTreeService service = new SpanTreeService(repository);

    @Test
    void traceSpans_returnsTree() {
        SpanNode node = new SpanNode();
        node.setSpanId("s1");
        when(repository.findSpans("c-1", "t-1")).thenReturn(List.of(node));

        SpanTree tree = service.traceSpans("c-1", "t-1");
        assertThat(tree.getTraceId()).isEqualTo("t-1");
        assertThat(tree.getSpans()).containsExactly(node);
    }

    @Test
    void traceSpans_empty_notFound() {
        when(repository.findSpans("c-1", "t-404")).thenReturn(List.of());
        assertThatThrownBy(() -> service.traceSpans("c-1", "t-404"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void spanDetail_present() {
        SpanDetail detail = new SpanDetail();
        detail.setSpanId("s1");
        when(repository.findSpan("c-1", "t-1", "s1")).thenReturn(Optional.of(detail));
        assertThat(service.spanDetail("c-1", "t-1", "s1")).isSameAs(detail);
    }

    @Test
    void spanDetail_missing_notFound() {
        when(repository.findSpan("c-1", "t-1", "s-404")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.spanDetail("c-1", "t-1", "s-404"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void spanDetail_blankPathParams_badRequest() {
        assertThatThrownBy(() -> service.spanDetail("c-1", "t-1", ""))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }
}

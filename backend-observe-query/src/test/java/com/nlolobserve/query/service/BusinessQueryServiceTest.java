package com.nlolobserve.query.service;

import java.time.LocalDate;
import java.util.List;

import com.nlolobserve.common.dto.BusinessItem;
import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.query.repo.QueryRepository;
import com.nlolobserve.query.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessQueryServiceTest {

    private final QueryRepository repository = mock(QueryRepository.class);
    private final BusinessQueryService service = new BusinessQueryService(repository);

    @Test
    void queryBusinesses_validRange_delegatesToRepo() {
        BusinessItem item = new BusinessItem();
        item.setBizId("b-001");
        when(repository.findBusinessItems("c-1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), "ORDER"))
                .thenReturn(List.of(item));

        List<BusinessItem> result = service.queryBusinesses("c-1", "2026-09-01", "2026-09-28", "ORDER");
        assertThat(result).containsExactly(item);
        verify(repository).findBusinessItems("c-1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), "ORDER");
    }

    @Test
    void queryBusinesses_blankCustomerId_badRequest() {
        assertThatThrownBy(() -> service.queryBusinesses(" ", "2026-09-01", "2026-09-28", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void queryBusinesses_emptyResult_notFound() {
        when(repository.findBusinessItems("c-1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), null))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.queryBusinesses("c-1", "2026-09-01", "2026-09-28", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}

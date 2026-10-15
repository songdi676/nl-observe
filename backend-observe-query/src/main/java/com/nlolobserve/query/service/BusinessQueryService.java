package com.nlolobserve.query.service;

import java.util.List;

import com.nlolobserve.common.dto.BusinessItem;
import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.query.repo.QueryRepository;
import com.nlolobserve.query.web.ApiException;
import org.springframework.stereotype.Service;

/**
 * 业务列表查询（详设 §2.1）：参数校验 → 只读查询 → 无数据 40401。
 */
@Service
public class BusinessQueryService {

    private final QueryRepository repository;

    public BusinessQueryService(QueryRepository repository) {
        this.repository = repository;
    }

    public List<BusinessItem> queryBusinesses(String customerId, String from, String to, String bizCode) {
        if (customerId == null || customerId.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "customerId 必填");
        }
        QueryRange range = QueryRange.parse(from, to);
        List<BusinessItem> items = repository.findBusinessItems(
                customerId, range.from(), range.to(), bizCode);
        if (items.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return items;
    }
}

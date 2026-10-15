package com.nlolobserve.query.web;

import java.util.List;

import com.nlolobserve.common.dto.BusinessItem;
import com.nlolobserve.common.web.ApiResult;
import com.nlolobserve.query.service.BusinessQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务列表 API（详设 §2.1）：GET /api/observe/customers/{customerId}/businesses。
 * from/to 必填、bizCode 可选；缺失/非法由 service 校验统一映射 40001。
 */
@RestController
@RequestMapping("/api/observe")
public class BusinessesController {

    private final BusinessQueryService service;

    public BusinessesController(BusinessQueryService service) {
        this.service = service;
    }

    @GetMapping("/customers/{customerId}/businesses")
    public ApiResult<List<BusinessItem>> businesses(@PathVariable String customerId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String bizCode) {
        return ApiResult.ok(service.queryBusinesses(customerId, from, to, bizCode));
    }
}

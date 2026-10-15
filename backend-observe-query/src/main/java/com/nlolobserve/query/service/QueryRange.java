package com.nlolobserve.query.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.nlolobserve.common.web.ErrorCode;
import com.nlolobserve.query.web.ApiException;

/**
 * 查询时间范围（详设 §1.4 参数校验）：from/to 必填、格式 yyyy-MM-dd、from≤to、跨度 ≤31 天，
 * 违反即 {@link ErrorCode#BAD_REQUEST}（40001/400）。
 */
public record QueryRange(LocalDate from, LocalDate to) {

    /** 最大查询跨度（天，含首尾，详设 §1.4） */
    public static final int MAX_SPAN_DAYS = 31;

    public static QueryRange parse(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "from/to 必填（yyyy-MM-dd）");
        }
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        if (fromDate.isAfter(toDate)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "from 不得晚于 to");
        }
        long spanDays = toDate.toEpochDay() - fromDate.toEpochDay() + 1;
        if (spanDays > MAX_SPAN_DAYS) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "查询跨度超过 " + MAX_SPAN_DAYS + " 天");
        }
        return new QueryRange(fromDate, toDate);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "日期格式须为 yyyy-MM-dd: " + value);
        }
    }
}

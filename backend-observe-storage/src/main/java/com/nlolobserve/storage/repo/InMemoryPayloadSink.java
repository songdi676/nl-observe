package com.nlolobserve.storage.repo;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T07 骨架桩仓库公共计数基类：仅记录已消费载荷条数，打通「消费 → 写库成功 → 提交 offset」链路；
 * T08 起由 JdbcTemplate batch insert 实现替换（本基类随之删除）。
 */
abstract class InMemoryPayloadSink {

    private final AtomicLong processedRecords = new AtomicLong();

    void accept(List<String> payloads) {
        processedRecords.addAndGet(payloads.size());
    }

    /** 已写入（桩：已计数）的记录条数 */
    public long processedRecords() {
        return processedRecords.get();
    }
}

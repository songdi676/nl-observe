package com.nlolobserve.storage.repo;

import java.util.List;

/**
 * otel_logs 写入仓库（详设 §1.3 repo/）。
 * T07 骨架契约：接收一批来自 nl-observe.otlp.logs 的 OTLP/JSON 原始载荷；
 * 实现抛异常表示写失败（消费端据此重试/跳过）。
 * T08 落地：row_hash 计算 + JDBC 命名参数 batch insert。
 */
public interface LogsRepository {

    /** 写一批 logs 原始载荷（失败抛异常） */
    void write(List<String> payloads);
}

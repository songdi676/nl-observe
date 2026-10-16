package com.nlolobserve.storage.repo;

import java.util.List;

/**
 * otel_spans 写入仓库（详设 §1.3 repo/）。
 * T07 骨架契约：接收一批来自 nl-observe.otlp.traces 的 OTLP/JSON 原始载荷；
 * 实现抛异常表示写失败（消费端据此重试/跳过）。
 * T08 落地：OTLP 解析为行模型 + JDBC 命名参数 batch insert（500/批）。
 *
 * <p>T10 评审 Critical（AC-2 span 树含 events）：OTLP span events 已由接收端保留在
 * Kafka 载荷（OtlpJsonWriter events 数组），写路径必须序列化落 {@code events_json} 列
 * （契约见 {@link com.nlolobserve.common.ddl.DdlScripts#OTEL_SPANS} 注释：
 * [{"name","time":ISO-8601 UTC,"attributes":扁平键值}]，无事件写 '[]'）——
 * 否则查询端 events 恒为空数组，T19 挂起/恢复时间线标记不可达。
 */
public interface SpansRepository {

    /** 写一批 spans 原始载荷（失败抛异常） */
    void write(List<String> payloads);
}

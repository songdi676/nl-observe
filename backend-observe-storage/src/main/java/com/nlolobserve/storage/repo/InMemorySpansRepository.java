package com.nlolobserve.storage.repo;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * T07 占位 spans 仓库（计数桩，见 {@link InMemoryPayloadSink}）；T08 替换为 JDBC batch 写入。
 */
@Component
public class InMemorySpansRepository extends InMemoryPayloadSink implements SpansRepository {

    @Override
    public void write(List<String> payloads) {
        accept(payloads);
    }
}

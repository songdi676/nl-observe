package com.nlolobserve.storage.consumer;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 批消费写入公共逻辑（详设 §1.3/§4.2，T07 验收）：
 * 写库成功后才 ack（MANUAL_IMMEDIATE 同步提交 offset → at-least-once）；
 * 写失败重试 {@value #WRITE_RETRIES} 次（初次 + 3 次重试 = 4 次尝试）后 error 日志 + 跳过该批不提交
 * （依赖重投 + 写入侧 REPLACE INTO 去重兜底重复）。
 * T08 评审 backport（T07 遗留）：重试间线性退避（基数 {@value #DEFAULT_RETRY_BACKOFF_MS}ms × 重试序号），
 * 避免 Databend 故障时对数据库的忙轮询；基数为 0 时退避关闭（单测口径）。
 * 失败分流（T08 评审 F-1）：仓库层已把解析类失败按记录粒度隔离（不抛出），
 * 本处重试只面向瞬态写库故障——永久性解析错误不会经此路径整批重投致分区卡死。
 */
@Component
public class BatchWriteSupport {

    private static final Logger LOG = LoggerFactory.getLogger(BatchWriteSupport.class);

    /** 初次写失败后的重试次数（详设 §4.2「批内重试 3 次」） */
    static final int WRITE_RETRIES = 3;

    /** 生产默认退避基数（ms）：第 n 次重试前睡眠 n × 基数 */
    public static final long DEFAULT_RETRY_BACKOFF_MS = 500L;

    private final long retryBackoffBaseMs;

    public BatchWriteSupport() {
        this(DEFAULT_RETRY_BACKOFF_MS);
    }

    /** @param retryBackoffBaseMs 线性退避基数（ms），0 表示关闭退避（单测用极小值） */
    BatchWriteSupport(long retryBackoffBaseMs) {
        this.retryBackoffBaseMs = retryBackoffBaseMs;
    }

    /** 一批载荷 → 仓库写入；抛异常表示写失败 */
    @FunctionalInterface
    public interface PayloadWriter {
        void write(List<String> payloads);
    }

    /**
     * @param records    本批 Kafka 记录
     * @param ack        手动提交句柄（仅写成功路径调用）
     * @param writer     仓库写入动作
     * @param signalName 信号名（traces/metrics/logs，日志定位用）
     */
    public void handleBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack,
                            PayloadWriter writer, String signalName) {
        List<String> payloads = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            payloads.add(record.value());
        }
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                writer.write(payloads);
                ack.acknowledge();
                return;
            } catch (RuntimeException e) {
                if (attempts > WRITE_RETRIES) {
                    LOG.error("{} 批写入失败，重试 {} 次后跳过该批（offset 不提交，等待重投），批大小={}",
                            signalName, WRITE_RETRIES, payloads.size(), e);
                    return;
                }
                LOG.warn("{} 批写入失败，第 {}/{} 次重试（退避 {}ms）", signalName, attempts, WRITE_RETRIES,
                        retryBackoffBaseMs * attempts, e);
                sleepBackoff(retryBackoffBaseMs * attempts);
            }
        }
    }

    /** 退避睡眠（独立方法：单测以极小基数/0 覆盖而不真实睡眠；子类可替换为记录断言）。 */
    void sleepBackoff(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

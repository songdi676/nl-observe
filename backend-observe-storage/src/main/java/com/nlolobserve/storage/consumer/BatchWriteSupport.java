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
 * （依赖重投 + Replacing 去重兜底重复）。
 */
@Component
public class BatchWriteSupport {

    private static final Logger LOG = LoggerFactory.getLogger(BatchWriteSupport.class);

    /** 初次写失败后的重试次数（详设 §4.2「批内重试 3 次」） */
    static final int WRITE_RETRIES = 3;

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
                LOG.warn("{} 批写入失败，第 {}/{} 次重试", signalName, attempts, WRITE_RETRIES, e);
            }
        }
    }
}

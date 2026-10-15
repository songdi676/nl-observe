package com.nlolobserve.storage.consumer;

import java.util.List;

import com.nlolobserve.common.constant.Topics;
import com.nlolobserve.storage.repo.LogsRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * nl-observe.otlp.logs 独立 listener（详设 §1.3 consumer/，T07）。
 * 消费组与提交语义同 {@link TracesConsumer}（三 listener 相互独立）。
 */
@Component
public class LogsConsumer {

    private final LogsRepository logsRepository;
    private final BatchWriteSupport batchWriteSupport;

    public LogsConsumer(LogsRepository logsRepository, BatchWriteSupport batchWriteSupport) {
        this.logsRepository = logsRepository;
        this.batchWriteSupport = batchWriteSupport;
    }

    @KafkaListener(id = "logs-listener", idIsGroup = false, topics = Topics.LOGS)
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        batchWriteSupport.handleBatch(records, ack, logsRepository::write, "logs");
    }
}

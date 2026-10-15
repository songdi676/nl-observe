package com.nlolobserve.storage.consumer;

import java.util.List;

import com.nlolobserve.common.constant.Topics;
import com.nlolobserve.storage.repo.SpansRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * nl-observe.otlp.traces 独立 listener（详设 §1.3 consumer/，T07）。
 * 消费组 = spring.kafka.consumer.group-id（observe.kafka.consumer-group，nl-observe-storage）；
 * batch + 手动提交语义见 {@link BatchWriteSupport}。
 */
@Component
public class TracesConsumer {

    private final SpansRepository spansRepository;
    private final BatchWriteSupport batchWriteSupport;

    public TracesConsumer(SpansRepository spansRepository, BatchWriteSupport batchWriteSupport) {
        this.spansRepository = spansRepository;
        this.batchWriteSupport = batchWriteSupport;
    }

    @KafkaListener(id = "traces-listener", idIsGroup = false, topics = Topics.TRACES)
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        batchWriteSupport.handleBatch(records, ack, spansRepository::write, "traces");
    }
}

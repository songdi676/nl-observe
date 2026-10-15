package com.nlolobserve.storage.consumer;

import java.util.List;

import com.nlolobserve.common.constant.Topics;
import com.nlolobserve.storage.repo.MetricsRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * nl-observe.otlp.metrics 独立 listener（详设 §1.3 consumer/，T07）。
 * 消费组与提交语义同 {@link TracesConsumer}（三 listener 相互独立）。
 */
@Component
public class MetricsConsumer {

    private final MetricsRepository metricsRepository;
    private final BatchWriteSupport batchWriteSupport;

    public MetricsConsumer(MetricsRepository metricsRepository, BatchWriteSupport batchWriteSupport) {
        this.metricsRepository = metricsRepository;
        this.batchWriteSupport = batchWriteSupport;
    }

    @KafkaListener(id = "metrics-listener", idIsGroup = false, topics = Topics.METRICS)
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        batchWriteSupport.handleBatch(records, ack, metricsRepository::write, "metrics");
    }
}

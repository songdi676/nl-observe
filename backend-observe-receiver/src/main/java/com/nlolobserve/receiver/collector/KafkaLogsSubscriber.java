package com.nlolobserve.receiver.collector;

import java.util.concurrent.Flow;

import com.nlolobserve.receiver.config.KafkaExporterProperties;
import com.nlolobserve.receiver.metrics.DroppedCounter;
import io.mishmash.opentelemetry.server.collector.LogsSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * T05：log record 写入订阅者。
 * 每个扁平化 log record 重建为单记录 OTLP/JSON 信封写入 nl-observe.otlp.logs，
 * key = resource 属性 nl.customer_id（详设 §1.2/§5.3）。
 */
@Component
public class KafkaLogsSubscriber extends KafkaSignalSubscriber implements LogsSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaLogsSubscriber.class);

    public KafkaLogsSubscriber(KafkaTemplate<String, String> kafka,
                               KafkaExporterProperties props,
                               DroppedCounter dropped) {
        super(kafka, props, dropped);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        requestAll(subscription);
    }

    @Override
    public void onNext(io.mishmash.opentelemetry.server.collector.Log log) {
        send(props.getLogsTopic(),
                OtlpJsonWriter.customerId(log.getResourceAttributes()),
                OtlpJsonWriter.logsEnvelope(log),
                "logs");
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.error("logs 订阅分发失败: {}", throwable);
    }

    @Override
    public void onComplete() {
        // 批结束，无状态需清理
    }
}

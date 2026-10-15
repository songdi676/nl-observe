package com.nlolobserve.receiver.collector;

import java.util.concurrent.Flow;

import com.nlolobserve.receiver.config.KafkaExporterProperties;
import com.nlolobserve.receiver.metrics.DroppedCounter;
import io.mishmash.opentelemetry.server.collector.SpansSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * T05：span 写入订阅者（替换 T04 占位实现）。
 * 每个扁平化 span 重建为单记录 OTLP/JSON 信封写入 nl-observe.otlp.traces，
 * key = resource 属性 nl.customer_id（详设 §1.2/§5.3）。
 */
@Component
public class KafkaSpansSubscriber extends KafkaSignalSubscriber implements SpansSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaSpansSubscriber.class);

    public KafkaSpansSubscriber(KafkaTemplate<String, String> kafka,
                                KafkaExporterProperties props,
                                DroppedCounter dropped) {
        super(kafka, props, dropped);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        requestAll(subscription);
    }

    @Override
    public void onNext(io.mishmash.opentelemetry.server.collector.Span span) {
        send(props.getTracesTopic(),
                OtlpJsonWriter.customerId(span.getResourceAttributes()),
                OtlpJsonWriter.tracesEnvelope(span),
                "traces");
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.error("traces 订阅分发失败: {}", throwable);
    }

    @Override
    public void onComplete() {
        // 批结束，无状态需清理
    }
}

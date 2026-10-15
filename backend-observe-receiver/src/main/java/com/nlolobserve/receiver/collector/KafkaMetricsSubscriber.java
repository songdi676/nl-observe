package com.nlolobserve.receiver.collector;

import java.util.concurrent.Flow;

import com.nlolobserve.receiver.config.KafkaExporterProperties;
import com.nlolobserve.receiver.metrics.DroppedCounter;
import io.mishmash.opentelemetry.server.collector.MetricDataPoint;
import io.mishmash.opentelemetry.server.collector.MetricsSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * T05：metric data point 写入订阅者（替换 T04 占位实现）。
 * 每个扁平化 data point 按 metric 类型重建为单记录 OTLP/JSON 信封写入 nl-observe.otlp.metrics，
 * key = resource 属性 nl.customer_id（详设 §1.2/§5.3）。
 */
@Component
public class KafkaMetricsSubscriber extends KafkaSignalSubscriber implements MetricsSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaMetricsSubscriber.class);

    public KafkaMetricsSubscriber(KafkaTemplate<String, String> kafka,
                                  KafkaExporterProperties props,
                                  DroppedCounter dropped) {
        super(kafka, props, dropped);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        requestAll(subscription);
    }

    @Override
    public void onNext(MetricDataPoint dataPoint) {
        send(props.getMetricsTopic(),
                OtlpJsonWriter.customerId(dataPoint.getResourceAttributes()),
                OtlpJsonWriter.metricsEnvelope(dataPoint),
                "metrics");
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.error("metrics 订阅分发失败: {}", throwable);
    }

    @Override
    public void onComplete() {
        // 批结束，无状态需清理
    }
}

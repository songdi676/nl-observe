package com.nlolobserve.receiver.collector;

import java.util.concurrent.Flow;
import java.util.logging.Logger;

import io.mishmash.opentelemetry.server.collector.MetricDataPoint;
import io.mishmash.opentelemetry.server.collector.MetricsSubscriber;
import org.springframework.stereotype.Component;

/**
 * T04 占位 metrics 订阅者（说明同 {@link PlaceholderSpansSubscriber}）；
 * T05 替换为 nl-observe.otlp.metrics 写入订阅者。
 */
@Component
public class PlaceholderMetricsSubscriber implements MetricsSubscriber {

    private static final Logger LOG = Logger.getLogger(PlaceholderMetricsSubscriber.class.getName());

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(MetricDataPoint dataPoint) {
        // T05：写入 nl-observe.otlp.metrics
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.severe("metrics 订阅分发失败: " + throwable);
    }

    @Override
    public void onComplete() {
        // 批结束，无状态需清理
    }
}

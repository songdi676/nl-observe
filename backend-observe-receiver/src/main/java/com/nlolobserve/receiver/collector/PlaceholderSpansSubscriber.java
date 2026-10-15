package com.nlolobserve.receiver.collector;

import java.util.concurrent.Flow;
import java.util.logging.Logger;

import io.mishmash.opentelemetry.server.collector.Span;
import io.mishmash.opentelemetry.server.collector.SpansSubscriber;
import org.springframework.stereotype.Component;

/**
 * T04 占位 span 订阅者：collector-embedded 无内建 exporter（T01 poc-notes §1），
 * 且 collector 无订阅者时 OTLP 入口返回 500——T04 先以本占位订阅者打通接收链路，
 * T05 将其替换为 Kafka 三 topic 写入订阅者（载荷/失败计数按详设 §1.2/§4.2）。
 */
@Component
public class PlaceholderSpansSubscriber implements SpansSubscriber {

    private static final Logger LOG = Logger.getLogger(PlaceholderSpansSubscriber.class.getName());

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        // 立即拉取全部数据，避免批处理 future 挂起导致 OTLP 入口超时
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(Span span) {
        // T05：写入 nl-observe.otlp.traces（key=nl.customer_id，载荷 OTLP/JSON）
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.severe("span 订阅分发失败: " + throwable);
    }

    @Override
    public void onComplete() {
        // 批结束，无状态需清理
    }
}

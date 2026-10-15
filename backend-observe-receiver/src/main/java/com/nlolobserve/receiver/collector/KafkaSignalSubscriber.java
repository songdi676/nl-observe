package com.nlolobserve.receiver.collector;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.nlolobserve.receiver.config.KafkaExporterProperties;
import com.nlolobserve.receiver.metrics.DroppedCounter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 写入订阅者公共骨架（T05）。
 * AC-10（NFR-02）：入口响应绝不被 broker 阻塞。KafkaTemplate.send() 在 broker 不可达时
 * 可阻塞至 max-block-ms(5s)（metadata 获取超时），因此 send 统一卸载到专用有界队列
 * 后台执行器（T05 评审 Critical-1：若在 collector 分发线程内联执行，N 条记录将 N×5s
 * 串行阻塞 → N≥3 时入口确定性 503，并耗尽 Vert.x worker 池）；入口只入队即返回。
 * 丢弃计数（nl.receiver.dropped）三路径：
 * 1) 入队成功后的异步失败 —— whenComplete 异常分支（KafkaTemplate.doSend 把
 *    producer.send 的同步抛出转换为 failed future，两类失败统一经此计数）；
 * 2) 发送积压队列满 —— 拒绝即计数+warn（fire-and-forget 降级）；
 * 3) 执行器线程上 send 自身同步抛出 —— 防御性计数（doSend 转换后正常不可达）。
 */
abstract class KafkaSignalSubscriber {

    /** 发送积压上限；超出即拒绝新发送并计 dropped（入口恒快，积压有界）。 */
    private static final int SEND_QUEUE_CAPACITY = 4096;

    protected final KafkaTemplate<String, String> kafka;
    protected final KafkaExporterProperties props;
    protected final DroppedCounter dropped;

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** 专用有界队列执行器：max-block（≤5s）阻塞与异步发送均发生在后台 sender 线程。 */
    private final ExecutorService sender = newSender();

    protected KafkaSignalSubscriber(KafkaTemplate<String, String> kafka,
                                    KafkaExporterProperties props,
                                    DroppedCounter dropped) {
        this.kafka = kafka;
        this.props = props;
        this.dropped = dropped;
    }

    /** 仅入队发送任务：broker 不可达时 producer 的 max-block 阻塞发生在后台线程，入口立即返回（AC-10）。 */
    protected final void send(String topic, String key, String payload, String signal) {
        sender.execute(new SendTask(topic, key, payload, signal));
    }

    /** collector-embedded 硬约束（T01）：onSubscribe 必须立即无界拉取，否则批处理 future 挂起致入口超时。 */
    protected static void requestAll(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }

    /** 上下文关闭时停掉发送线程（在途发送放弃，与 fire-and-forget 语义一致）。 */
    @PreDestroy
    void shutdownSender() {
        sender.shutdownNow();
    }

    private ThreadPoolExecutor newSender() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "kafka-signal-sender-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                (task, executor) -> {
                    SendTask rejected = (SendTask) task;
                    dropped.increment();
                    log.warn("Kafka 发送积压已满，载荷丢弃: signal={} topic={} key={}",
                            rejected.signal, rejected.topic, forLog(rejected.key));
                });
    }

    /**
     * 发送 OTLP/JSON 载荷至 topic；失败（异步/同步/积压拒绝）仅计数+日志，不向 collector 传播异常。
     */
    private final class SendTask implements Runnable {

        private final String topic;
        private final String key;
        private final String payload;
        private final String signal;

        private SendTask(String topic, String key, String payload, String signal) {
            this.topic = topic;
            this.key = key;
            this.payload = payload;
            this.signal = signal;
        }

        @Override
        public void run() {
            try {
                kafka.send(topic, key, payload)
                        .whenComplete((metadata, ex) -> {
                            if (ex != null) {
                                dropped.increment();
                                log.warn("Kafka 发送异步失败: signal={} topic={} key={}",
                                        signal, topic, forLog(key), ex);
                            }
                        });
            } catch (Exception e) {
                // 防御：KafkaTemplate.doSend 通常已把同步抛出转成 failed future（whenComplete 计数），
                // 此分支仅覆盖 send 尚未返回 future 即抛出的极端情形，两分支互斥、恰好计一次。
                dropped.increment();
                log.warn("Kafka 发送同步失败: signal={} topic={} key={}", signal, topic, forLog(key), e);
            }
        }
    }

    /** 日志加固（安全评审 F5）：key 中控制字符替换为 '_'，防 CRLF 注入伪造日志行；超长截断。 */
    private static String forLog(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("\\p{Cntrl}", "_");
        return sanitized.length() > 64 ? sanitized.substring(0, 64) : sanitized;
    }

}

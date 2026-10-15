package com.nlolobserve.storage.consumer;

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * T07 验收：写库成功后才提交 offset（at-least-once）；
 * 写失败重试 3 次（初次 + 3 次重试 = 4 次调用）后 error 日志 + 跳过该批不提交。
 */
class BatchWriteSupportTest {

    private final BatchWriteSupport support = new BatchWriteSupport();
    private Acknowledgment ack;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        ack = mock(Acknowledgment.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(BatchWriteSupport.class);
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(BatchWriteSupport.class);
        logger.detachAppender(logAppender);
    }

    @Test
    void writeSucceeds_acksOnce() {
        AtomicInteger calls = new AtomicInteger();
        support.handleBatch(records(2), ack, payloads -> calls.incrementAndGet(), "traces");
        assertThat(calls.get()).as("写成功只调用一次").isEqualTo(1);
        verify(ack).acknowledge();
    }

    @Test
    void writeFailsTwiceThenSucceeds_acksAfterThirdAttempt() {
        AtomicInteger calls = new AtomicInteger();
        support.handleBatch(records(1), ack, payloads -> {
            if (calls.incrementAndGet() <= 2) {
                throw new IllegalStateException("db down");
            }
        }, "metrics");
        assertThat(calls.get()).as("失败 2 次后第 3 次成功").isEqualTo(3);
        verify(ack).acknowledge();
    }

    @Test
    void writeAlwaysFails_retriesThreeTimesThenSkipsWithoutAck() {
        AtomicInteger calls = new AtomicInteger();
        support.handleBatch(records(3), ack, payloads -> {
            calls.incrementAndGet();
            throw new IllegalStateException("db down");
        }, "logs");
        assertThat(calls.get()).as("初次 + 重试 3 次 = 4 次写入尝试").isEqualTo(4);
        verify(ack, never()).acknowledge();
        assertThat(logAppender.list)
                .as("重试耗尽后必须记录 error 日志并说明跳过该批")
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("跳过该批")
                        && e.getFormattedMessage().contains("logs"));
    }

    @Test
    void payloadsForwardedInOrder() {
        List<String> seen = new ArrayList<>();
        support.handleBatch(records(2), ack, seen::addAll, "traces");
        assertThat(seen).containsExactly("payload-0", "payload-1");
    }

    private static List<ConsumerRecord<String, String>> records(int count) {
        List<ConsumerRecord<String, String>> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ConsumerRecord<>("nl-observe.otlp.traces", 0, i, "cust-1", "payload-" + i));
        }
        return list;
    }
}

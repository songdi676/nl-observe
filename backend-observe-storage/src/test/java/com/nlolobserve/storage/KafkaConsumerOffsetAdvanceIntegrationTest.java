package com.nlolobserve.storage;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.nlolobserve.common.constant.Topics;
import com.nlolobserve.storage.repo.LogsRepository;
import com.nlolobserve.storage.repo.MetricsRepository;
import com.nlolobserve.storage.repo.SpansRepository;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import static org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * T07 验收（集成，Testcontainers Kafka，env-gated：Docker/镜像不可得时按 assumption 跳过）：
 * 向三 topic 灌消息 → 消费组 nl-observe-storage 消费、写库成功后 offset 提交推进。
 * T08 起仓库为 JDBC 实现（写真实 Databend 属 StorageDatabendIntegrationTest 职责）：
 * 本用例以 @MockitoBean 仓库桩验证「消费 → 写成功 → 提交」链路，不依赖 Databend。
 * Kafka 镜像可用 NL_OBSERVE_KAFKA_IMAGE 环境变量覆盖（T07 评审遗留 backport，同 receiver 口径）。
 */
@SpringBootTest(properties = {
        "observe.databend.url=jdbc:databend://127.0.0.1:8000/observe?presigned_url_disabled=true",
        "observe.databend.check-on-startup=false",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"
})
class KafkaConsumerOffsetAdvanceIntegrationTest {

    private static final String CONSUMER_GROUP = "nl-observe-storage";
    private static final long AWAIT_BUDGET_MS = 60_000;
    private static final String DEFAULT_IMAGE_NAME = "apache/kafka:latest";

    private static KafkaContainer kafka;

    @BeforeAll
    static void startKafka() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用：跳过 Kafka 集成测试");
        String imageName = envOr("NL_OBSERVE_KAFKA_IMAGE", DEFAULT_IMAGE_NAME);
        try {
            kafka = new KafkaContainer(DockerImageName.parse(imageName));
            kafka.start();
        } catch (Throwable t) {
            if (kafka != null) {
                kafka.stop();
                kafka = null;
            }
            assumeTrue(false, "Kafka 镜像 " + imageName + " 不可得：跳过（人工环境验证），原因: " + t);
        }
    }

    @AfterAll
    static void stopKafka() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    @DynamicPropertySource
    static void kafkaBootstrap(DynamicPropertyRegistry registry) {
        if (kafka != null) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private SpansRepository spansRepository;

    @MockitoBean
    private MetricsRepository metricsRepository;

    @MockitoBean
    private LogsRepository logsRepository;

    @Test
    void sendToThreeTopics_consumedAndOffsetsAdvance() throws Exception {
        sendOneToEachTopic();

        // offset 推进以「三 listener 各自写库成功 + 手动提交」为前提；
        // 仓库桩写调用再经 verify(timeout) 佐证（写失败不 ack，位点不会推进）
        awaitCommittedOffsetsAdvanced();
        verify(spansRepository, timeout(AWAIT_BUDGET_MS).atLeast(1)).write(anyList());
        verify(metricsRepository, timeout(AWAIT_BUDGET_MS).atLeast(1)).write(anyList());
        verify(logsRepository, timeout(AWAIT_BUDGET_MS).atLeast(1)).write(anyList());
    }

    private void sendOneToEachTopic() throws Exception {
        kafkaTemplate.send(Topics.TRACES, "cust-1", "{\"resourceSpans\":[]}").get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(Topics.METRICS, "cust-1", "{\"resourceMetrics\":[]}").get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(Topics.LOGS, "cust-1", "{\"resourceLogs\":[]}").get(10, TimeUnit.SECONDS);
    }

    private void awaitCommittedOffsetsAdvanced() throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_BUDGET_MS;
        while (true) {
            Map<TopicPartition, OffsetAndMetadata> committed = committedOffsets();
            boolean allAdvanced = hasAdvanced(committed, Topics.TRACES)
                    && hasAdvanced(committed, Topics.METRICS)
                    && hasAdvanced(committed, Topics.LOGS);
            if (allAdvanced) {
                assertThat(groupExists()).as("消费组 %s 应存在", CONSUMER_GROUP).isTrue();
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("offset 未在时限内推进，已提交位点: " + committed);
            }
            Thread.sleep(200);
        }
    }

    private Map<TopicPartition, OffsetAndMetadata> committedOffsets() throws Exception {
        Properties props = new Properties();
        props.put(BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            return admin.listConsumerGroupOffsets(CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
        }
    }

    private boolean groupExists() throws Exception {
        Properties props = new Properties();
        props.put(BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            for (ConsumerGroupListing listing : admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS)) {
                if (CONSUMER_GROUP.equals(listing.groupId())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean hasAdvanced(Map<TopicPartition, OffsetAndMetadata> committed, String topic) {
        return committed.entrySet().stream()
                .filter(e -> e.getKey().topic().equals(topic))
                .anyMatch(e -> e.getValue().offset() >= 1);
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}

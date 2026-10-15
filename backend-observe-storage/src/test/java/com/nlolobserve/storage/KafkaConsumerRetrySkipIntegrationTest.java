package com.nlolobserve.storage;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
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
import com.nlolobserve.storage.repo.SpansRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * T07 验收（集成，Testcontainers Kafka，env-gated）：
 * repository 抛错 → 每批重试 3 次（初次 + 3 重试）后跳过、offset 不提交（重投兜底）。
 * 失败期间同一批被反复重投，write 调用次数会持续增长，断言 atLeast(4)。
 */
@SpringBootTest(properties = {
        "observe.databend.url=jdbc:databend://127.0.0.1:8000/observe?presigned_url_disabled=true",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"
})
class KafkaConsumerRetrySkipIntegrationTest {

    private static final String CONSUMER_GROUP = "nl-observe-storage";

    private static KafkaContainer kafka;

    @BeforeAll
    static void startKafka() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用：跳过 Kafka 集成测试");
        try {
            kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:latest"));
            kafka.start();
        } catch (Throwable t) {
            if (kafka != null) {
                kafka.stop();
                kafka = null;
            }
            assumeTrue(false, "Kafka 镜像不可得：跳过（人工环境验证），原因: " + t);
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

    @MockitoBean
    private SpansRepository spansRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void writeAlwaysFails_retriesPerBatchAndOffsetNeverCommitted() throws Exception {
        doThrow(new IllegalStateException("db down")).when(spansRepository).write(anyList());

        kafkaTemplate.send(Topics.TRACES, "cust-1", "{\"resourceSpans\":[]}").get(10, TimeUnit.SECONDS);

        // 每批 1 次初始 + 3 次重试；失败批被重投，调用次数持续增长
        verify(spansRepository, org.mockito.Mockito.timeout(30_000).atLeast(4)).write(anyList());

        // 消费方须以 nl-observe-storage 组身份加入（否则下述位点断言空转）
        assertThat(groupExists())
                .as("listener 应加入消费组 %s（id 不得兼作 group）", CONSUMER_GROUP)
                .isTrue();

        Map<TopicPartition, OffsetAndMetadata> committed = committedOffsets();
        assertThat(committed.keySet())
                .as("写失败且重试耗尽后 offset 不得提交（traces 无已提交位点），实际: %s", committed)
                .noneMatch(tp -> tp.topic().equals(Topics.TRACES));
    }

    private Map<TopicPartition, OffsetAndMetadata> committedOffsets() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            return admin.listConsumerGroupOffsets(CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
        }
    }

    private boolean groupExists() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            for (ConsumerGroupListing listing : admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS)) {
                if (CONSUMER_GROUP.equals(listing.groupId())) {
                    return true;
                }
            }
            return false;
        }
    }
}

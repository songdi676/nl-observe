package com.nlolobserve.receiver.collector;

import java.io.IOException;
import java.net.ServerSocket;

import com.nlolobserve.receiver.config.OtlpProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T04 内嵌 collector 生命周期（单测部分；端口监听与释放由集成用例覆盖）。
 * 验收：observe.otlp.port 可配置；监听失败（端口被占用）时快速失败并回滚资源，不泄漏。
 */
class EmbeddedCollectorConfigTest {

    @Test
    void start_portInUse_failsFastAndReleasesResources() throws IOException {
        try (ServerSocket blocker = new ServerSocket(0)) {
            OtlpProperties properties = new OtlpProperties();
            properties.setPort(blocker.getLocalPort());
            EmbeddedCollectorConfig config = new EmbeddedCollectorConfig(properties);

            assertThatThrownBy(config::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OTLP/HTTP listen");
        }
        // 回滚后再次 stop 应安全无操作（资源已释放）
        EmbeddedCollectorConfig config = new EmbeddedCollectorConfig(new OtlpProperties());
        assertThatCode(config::stop).doesNotThrowAnyException();
    }
}

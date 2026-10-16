package com.nlolobserve.receiver.filter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nlolobserve.receiver.config.SecurityFilterProperties;
import com.nlolobserve.receiver.metrics.RejectedCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T06：Spring MVC 侧 IP 白名单过滤器单测——/config/producer 等只读运维端点
 * 按 getRemoteAddr（直连口径）判定，不信任 X-Forwarded-For；非白名单 403 + 40301。
 */
class IpWhitelistFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SecurityFilterProperties properties;
    private RejectedCounter rejectedCounter;
    private IpWhitelistFilter filter;
    private final AtomicBoolean chainInvoked = new AtomicBoolean();
    private final FilterChain chain = (req, res) -> chainInvoked.set(true);

    @BeforeEach
    void setUp() {
        properties = new SecurityFilterProperties();
        properties.setAllowedCidrs(List.of("127.0.0.1/32", "10.0.0.0/8"));
        rejectedCounter = new RejectedCounter(new SimpleMeterRegistry());
        filter = new IpWhitelistFilter(properties, rejectedCounter, MAPPER);
        chainInvoked.set(false);
    }

    private MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/config/producer");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void whitelistedRemoteAddr_chainContinues() throws Exception {
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chainInvoked.get()).as("白名单内来源应放行").isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void whitelistedCidrRemoteAddr_chainContinues() throws Exception {
        MockHttpServletRequest request = requestFrom("10.1.8.18");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    void nonWhitelistedRemoteAddr_rejects403_uniformErrorCode() throws Exception {
        MockHttpServletRequest request = requestFrom("66.6.6.6");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chainInvoked.get()).as("非白名单来源不得进入 servlet 链").isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode json = MAPPER.readTree(response.getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(40301);
        // T06 评审 Minor：MVC 侧拒绝须计 nl.receiver.rejected{reason=ip}（与 OTLP 入口同口径，
        // 文档告警描述"OTLP/MVC 入口"由此成立）
        assertThat(rejectedCounter.count(RejectedCounter.REASON_IP)).isEqualTo(1.0);
    }

    @Test
    void xForwardedForIsNotTrusted() throws Exception {
        // 白名单外直连 IP 伪造 X-Forwarded-For 为白名单 IP：仍按直连口径拒绝
        MockHttpServletRequest request = requestFrom("66.6.6.6");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chainInvoked.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void invalidSecurityProperties_failsFast() {
        SecurityFilterProperties broken = new SecurityFilterProperties();
        broken.setMaxRequestBytes(0);
        assertThatThrownBy(() -> new IpWhitelistFilter(broken, rejectedCounter, MAPPER))
                .isInstanceOf(IllegalStateException.class);
    }
}

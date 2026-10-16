package com.nlolobserve.receiver.filter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T06：CIDR 白名单匹配内核单测——IPv4/IPv6 前缀、裸 IP、IPv4-mapped IPv6、
 * 非法条目 fail-fast、空列表 fail-closed、主机名拒绝（防 DNS 污染）。
 */
class CidrMatcherTest {

    @Test
    void ipv4Prefix_matchAndReject() throws Exception {
        CidrMatcher matcher = CidrMatcher.of(List.of("10.0.0.0/8"));
        assertThat(matcher.isAllowed(ip("10.1.2.3"))).isTrue();
        assertThat(matcher.isAllowed(ip("10.255.255.255"))).isTrue();
        assertThat(matcher.isAllowed(ip("11.0.0.1"))).isFalse();
        assertThat(matcher.isAllowed(ip("9.255.255.255"))).isFalse();
    }

    @Test
    void partialBytePrefix_matchAndReject() throws Exception {
        // /26：192.168.1.0 ~ 192.168.1.63 命中，.64 起不命中
        CidrMatcher matcher = CidrMatcher.of(List.of("192.168.1.0/26"));
        assertThat(matcher.isAllowed(ip("192.168.1.0"))).isTrue();
        assertThat(matcher.isAllowed(ip("192.168.1.63"))).isTrue();
        assertThat(matcher.isAllowed(ip("192.168.1.64"))).isFalse();
        assertThat(matcher.isAllowed(ip("192.168.2.0"))).isFalse();
    }

    @Test
    void bareIpv4_treatedAsSlash32() throws Exception {
        CidrMatcher matcher = CidrMatcher.of(List.of("1.2.3.4"));
        assertThat(matcher.isAllowed(ip("1.2.3.4"))).isTrue();
        assertThat(matcher.isAllowed(ip("1.2.3.5"))).isFalse();
    }

    @Test
    void ipv6Prefix_matchAndReject() throws Exception {
        CidrMatcher matcher = CidrMatcher.of(List.of("2001:db8::/32"));
        assertThat(matcher.isAllowed(ip("2001:db8:1234::1"))).isTrue();
        assertThat(matcher.isAllowed(ip("2001:db9::1"))).isFalse();
        assertThat(matcher.hasIpv6Entries()).isTrue();
    }

    @Test
    void ipv4MappedIpv6_matchesIpv4Entries() throws Exception {
        // 双栈形态：客户端地址为 ::ffff:10.x（IPv4-mapped IPv6），白名单为 IPv4 条目
        CidrMatcher matcher = CidrMatcher.of(List.of("10.0.0.0/8"));
        assertThat(matcher.isAllowed(ip("::ffff:10.1.2.3"))).isTrue();
        assertThat(matcher.isAllowed(ip("::ffff:11.1.2.3"))).isFalse();
    }

    @Test
    void multipleEntries_unionMatch() throws Exception {
        CidrMatcher matcher = CidrMatcher.of(List.of("10.0.0.0/8", "192.168.0.0/16"));
        assertThat(matcher.isAllowed(ip("10.1.1.1"))).isTrue();
        assertThat(matcher.isAllowed(ip("192.168.9.9"))).isTrue();
        assertThat(matcher.isAllowed(ip("172.16.0.1"))).isFalse();
    }

    @Test
    void emptyOrBlankList_failClosed() throws Exception {
        assertThat(CidrMatcher.of(List.of()).isAllowed(ip("127.0.0.1"))).isFalse();
        assertThat(CidrMatcher.of(null).isAllowed(ip("127.0.0.1"))).isFalse();
        // 空白条目被忽略，非空条目仍生效
        CidrMatcher matcher = CidrMatcher.of(List.of("10.0.0.0/8", "", "  "));
        assertThat(matcher.isAllowed(ip("10.1.1.1"))).isTrue();
        assertThat(matcher.isAllowed(ip("11.1.1.1"))).isFalse();
    }

    @Test
    void nullAddress_failClosed() {
        assertThat(CidrMatcher.of(List.of("0.0.0.0/0")).isAllowed((InetAddress) null)).isFalse();
    }

    @Test
    void invalidEntries_failFast() {
        // 前缀越界 / 非 IP 字面量 / 主机名 / Java 遗留短格式 / 前缀非数字
        String[] invalid = {
                "10.0.0.0/33", "10.0.0.0/-1", "256.1.1.1/8", "abc/24",
                "example.com/24", "example.com", "10.1.2", "1.2.3", "10.0.0.0/", "10.0.0.0/8x",
        };
        for (String entry : invalid) {
            assertThatThrownBy(() -> CidrMatcher.of(List.of(entry)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .as("非法条目 %s 必须 fail-fast", entry);
        }
    }

    @Test
    void stringLookup_invalidLiteralFailClosed() {
        CidrMatcher matcher = CidrMatcher.of(List.of("10.0.0.0/8"));
        assertThat(matcher.isAllowed("10.1.2.3")).isTrue();
        assertThat(matcher.isAllowed("11.1.2.3")).isFalse();
        // 非 IP 字面量（含主机名形态）解析失败 → 按拒绝处理，绝不放行
        assertThat(matcher.isAllowed("not.a.valid-ip")).isFalse();
    }

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }
}

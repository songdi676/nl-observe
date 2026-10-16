package com.nlolobserve.receiver.filter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * CIDR 白名单匹配器（T06，详设 §1.2 filter/IpWhitelistFilter 的匹配内核）。
 * 支持 IPv4/IPv6 前缀（如 10.0.0.0/8、2001:db8::/32）与裸 IP（等价 /32、/128）；
 * IPv4-mapped IPv6（::ffff:a.b.c.d）按 IPv4 口径参与匹配（双栈部署常见形态）。
 * 构造期 fail-fast：非法 CIDR 抛 IllegalArgumentException（安全参数错误不得静默放行）。
 * 空列表语义：拒绝一切（fail-closed）。
 */
public final class CidrMatcher {

    /**
     * 单条 CIDR：网络地址字节（已按前缀掩码）+ 前缀长度（位）。
     */
    private record Entry(byte[] network, int prefixLen) {

        boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLen / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remBits = prefixLen % 8;
            if (remBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Entry> entries;

    private CidrMatcher(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * 解析 CIDR 列表（空白条目忽略）。
     *
     * @param cidrs 形如 ["10.0.0.0/8", "192.168.1.0/24"]；非法条目抛 IllegalArgumentException
     * @return 匹配器
     */
    public static CidrMatcher of(List<String> cidrs) {
        List<Entry> parsed = new ArrayList<>();
        if (cidrs != null) {
            for (String cidr : cidrs) {
                if (cidr == null || cidr.isBlank()) {
                    continue;
                }
                parsed.add(parseEntry(cidr.trim()));
            }
        }
        return new CidrMatcher(parsed);
    }

    /**
     * 地址是否命中任一白名单条目。
     */
    public boolean isAllowed(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        for (Entry entry : entries) {
            if (entry.matches(bytes)) {
                return true;
            }
        }
        // IPv4-mapped IPv6（::ffff:x.x.x.x）按 IPv4 口径再试一次
        byte[] mapped = mappedIpv4(bytes);
        if (mapped != null) {
            for (Entry entry : entries) {
                if (entry.matches(mapped)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 按 IP 字面量判定（servlet getRemoteAddr 口径）；非字面量按拒绝（fail-closed）。
     * 严格字面量守卫（评审 F5/Nit）：null/空白/主机名一律拒绝——
     * InetAddress.getByName(null) 返回回环地址（容器边缘形态会意外命中含回环的白名单），
     * 主机名形态会触发 DNS 查询（与条目侧"禁止主机名"口径一致，防污染/TOCTOU）。
     */
    public boolean isAllowed(String ipLiteral) {
        if (!isIpLiteral(ipLiteral)) {
            return false;
        }
        try {
            return isAllowed(InetAddress.getByName(ipLiteral));
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    private static Entry parseEntry(String cidr) {
        int slash = cidr.indexOf('/');
        String hostPart = slash >= 0 ? cidr.substring(0, slash) : cidr;
        if (!isIpLiteral(hostPart)) {
            // 白名单只接受 IP 字面量：主机名会引入 DNS 解析（可被污染/TOCTOU），安全上不允许
            throw new IllegalArgumentException("非法 CIDR 条目（必须为 IP 字面量，禁止主机名）: " + cidr);
        }
        InetAddress base;
        try {
            base = InetAddress.getByName(hostPart);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("非法 CIDR 条目（地址不可解析）: " + cidr, e);
        }
        if (slash < 0) {
            return new Entry(base.getAddress(), base.getAddress().length * 8);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法 CIDR 条目（前缀不可解析）: " + cidr, e);
        }
        int bits = base.getAddress().length * 8;
        if (prefix < 0 || prefix > bits) {
            throw new IllegalArgumentException("非法 CIDR 条目（前缀 " + prefix + " 超出 " + bits + " 位）: " + cidr);
        }
        return new Entry(mask(base.getAddress(), prefix), prefix);
    }

    /**
     * 严格 IP 字面量判定：IPv4=4 组十进制（0-255）；IPv6=含 ':' 且仅十六进制/':'/'.'。
     * 拒绝 Java 遗留的短格式（如 "10.1.2"），避免白名单条目被意外归一。
     */
    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex && c != ':' && c != '.') {
                    return false;
                }
            }
            return true;
        }
        String[] groups = host.split("\\.");
        if (groups.length != 4) {
            return false;
        }
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 3) {
                return false;
            }
            for (int i = 0; i < group.length(); i++) {
                if (group.charAt(i) < '0' || group.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(group) > 255) {
                return false;
            }
        }
        return true;
    }

    private static byte[] mask(byte[] bytes, int prefixLen) {
        byte[] out = bytes.clone();
        int fullBytes = prefixLen / 8;
        int remBits = prefixLen % 8;
        if (remBits > 0) {
            int mask = (0xFF << (8 - remBits)) & 0xFF;
            out[fullBytes] = (byte) (out[fullBytes] & mask);
            fullBytes++;
        }
        for (int i = fullBytes; i < out.length; i++) {
            out[i] = 0;
        }
        return out;
    }

    /**
     * ::ffff:a.b.c.d → a.b.c.d 的 4 字节；其余返回 null。
     */
    private static byte[] mappedIpv4(byte[] bytes) {
        if (bytes.length != 16) {
            return null;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return null;
            }
        }
        if (bytes[10] != (byte) 0xFF || bytes[11] != (byte) 0xFF) {
            return null;
        }
        byte[] out = new byte[4];
        System.arraycopy(bytes, 12, out, 0, 4);
        return out;
    }

    /**
     * 是否含 IPv6 条目（仅诊断/文档用途）。
     */
    public boolean hasIpv6Entries() {
        for (Entry entry : entries) {
            if (entry.network().length == 16) {
                return true;
            }
        }
        return false;
    }
}

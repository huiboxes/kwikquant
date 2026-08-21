package com.kwikquant.shared.infra;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Security policy for user-controlled outbound HTTP destinations.
 *
 * <p>默认 SSRF 全禁:仅 HTTPS + 公网可路由。self-host/dev 场景(本地 Ollama/vLLM/LiteLLM 网关)
 * 经 {@link #configureAllowedPrivateHosts} 注入 allowlist 豁免(Spring 启动时由
 * OutboundPolicyConfigurer 读 {@code kwikquant.outbound.allow-private-hosts} 注入;
 * 未注入 = 全禁,SaaS prod 保持默认)。豁免面最小:仅命中 allowlist 的 host 放行 http+私网,
 * DNS 解析结果同步校验(防 rebinding)。
 */
public final class OutboundUrlPolicy {

    private static volatile Set<String> allowedPrivateHosts = Set.of();
    /** allowlist 解析出的地址集合(按字节比较)。{@code ::1} 与 {@code 0:0:0:0:0:0:0:1} 字面不同但字节同,须按 InetAddress equals 判。 */
    private static volatile Set<InetAddress> allowedPrivateAddresses = Set.of();

    private OutboundUrlPolicy() {}

    /** 启动期注入 self-host 豁免 allowlist(host 字面量/名字,小写);空集 = 全禁(默认)。 */
    public static void configureAllowedPrivateHosts(Set<String> hosts) {
        allowedPrivateHosts = Set.copyOf(hosts);
        Set<InetAddress> resolved = new HashSet<>();
        for (String host : hosts) {
            try {
                // getAllByName:localhost 双栈机同时收 127.0.0.1 与 ::1,IP 字面量直收
                java.util.Collections.addAll(resolved, InetAddress.getAllByName(host));
            } catch (Exception ignored) {
                // 非 IP 字面量/无法解析的条目仅保留 host 字面比较
            }
        }
        allowedPrivateAddresses = Set.copyOf(resolved);
    }

    private static boolean privateAllowed(String hostOrAddress) {
        return hostOrAddress != null && allowedPrivateHosts.contains(hostOrAddress.toLowerCase(Locale.ROOT));
    }

    /** 解析地址是否命中 allowlist(按 InetAddress 字节相等,兼容 IPv6 压缩/展开两种写法)。 */
    private static boolean addressAllowed(InetAddress address) {
        return address != null && allowedPrivateAddresses.contains(address);
    }

    public static URI validateAndNormalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }

        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl must be a valid URI", e);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("baseUrl must be a valid URI");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain userinfo, query, or fragment");
        }
        if (uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("baseUrl has an invalid port");
        }

        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank() || rawHost.indexOf('%') >= 0) {
            throw new IllegalArgumentException("baseUrl must contain a valid host");
        }
        String host = rawHost;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        host = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;

        String asciiHost;
        if (host.indexOf(':') >= 0) {
            asciiHost = host.toLowerCase(Locale.ROOT);
            if (!privateAllowed(asciiHost)) {
                validateLiteralAddress(asciiHost);
            }
        } else {
            try {
                asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("baseUrl must contain a valid host", e);
            }
            if (asciiHost.chars().allMatch(c -> Character.isDigit(c) || c == '.')) {
                if (!privateAllowed(asciiHost)) {
                    validateStrictIpv4Literal(asciiHost);
                }
            }
        }
        boolean hostExempt = privateAllowed(host) || privateAllowed(asciiHost);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            if (!("http".equalsIgnoreCase(uri.getScheme()) && hostExempt)) {
                throw new IllegalArgumentException(
                        "baseUrl must use HTTPS (self-host 私有网关需配置 kwikquant.outbound.allow-private-hosts)");
            }
        }
        if (host.equalsIgnoreCase("localhost") && !hostExempt) {
            throw new IllegalArgumentException(
                    "baseUrl host must be publicly routable (self-host 请配置 kwikquant.outbound.allow-private-hosts)");
        }

        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "";
        } else {
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }
        try {
            // scheme 保留原值(https 默认;http 仅 allowlist 豁免场景走到这里)
            return new URI(
                    uri.getScheme().toLowerCase(Locale.ROOT),
                    null,
                    asciiHost,
                    uri.getPort() == 443 ? -1 : uri.getPort(),
                    path,
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl must be a valid URI", e);
        }
    }

    public static void validateResolvedAddresses(List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("baseUrl host did not resolve");
        }
        // allowlist 内的私网地址放行(按字节比较,兼容 IPv6 展开形),其余仍须全公网(防 DNS rebinding)
        if (addresses.stream().anyMatch(address -> !isPublicUnicast(address) && !addressAllowed(address))) {
            throw new IllegalArgumentException("baseUrl host must resolve only to public addresses");
        }
    }

    static boolean isPublicUnicast(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            return a != 0
                    && a != 10
                    && a != 127
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254)
                    && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && b == 0)
                    && !(a == 192 && b == 168)
                    && !(a == 198 && (b == 18 || b == 19))
                    && !(a == 198 && b == 51)
                    && !(a == 203 && b == 0)
                    && a < 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            boolean globalUnicast = (first & 0xe0) == 0x20;
            boolean documentation = first == 0x20
                    && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            boolean teredo = first == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x01 && bytes[2] == 0 && bytes[3] == 0;
            boolean sixToFour = first == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x02;
            return globalUnicast && !documentation && !teredo && !sixToFour;
        }
        return false;
    }

    private static void validateLiteralAddress(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            validateResolvedAddresses(List.of(address));
        } catch (Exception e) {
            throw new IllegalArgumentException("baseUrl must contain a public IP address", e);
        }
    }

    private static void validateStrictIpv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("baseUrl must use canonical IPv4 notation");
        }
        for (String part : parts) {
            if (part.isEmpty() || (part.length() > 1 && part.startsWith("0"))) {
                throw new IllegalArgumentException("baseUrl must use canonical IPv4 notation");
            }
            try {
                if (Integer.parseInt(part) > 255) {
                    throw new IllegalArgumentException("baseUrl must use canonical IPv4 notation");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("baseUrl must use canonical IPv4 notation", e);
            }
        }
        validateLiteralAddress(host);
    }
}

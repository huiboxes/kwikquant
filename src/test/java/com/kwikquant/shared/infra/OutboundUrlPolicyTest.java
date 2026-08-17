package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboundUrlPolicyTest {

    @Test
    void normalizesPublicHttpsBaseUrl() {
        assertEquals(
                "https://api.example.com/v1",
                OutboundUrlPolicy.validateAndNormalizeBaseUrl(" HTTPS://API.EXAMPLE.COM:443/v1/ ")
                        .toString());
    }

    @Test
    void rejectsBlankMalformedAndOversizedPort() {
        // null/blank/语法错/端口越界(URI 解析不校验端口范围,策略层显式拦)
        assertThrows(IllegalArgumentException.class, () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl(null));
        assertThrows(IllegalArgumentException.class, () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("  "));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://exa mple.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://example.com:99999"));
    }

    @Test
    void rejectsUnsafeUriComponentsAndSchemes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("http://example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://user@example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://example.com?q=1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://example.com/#x"));
    }

    @Test
    void normalizesTrailingDotHost() {
        // 尾点根域名归一
        assertEquals(
                "https://example.com",
                OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://example.com.")
                        .toString());
    }

    @Test
    void rejectsNonAsciiHosts() {
        // Java URI 对非 ASCII host 返回 getHost()==null → 策略拒绝
        // (IDN/homoglyph 域名在解析层即被拦,不会进入后续连接阶段)
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://bücher.example"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://b%C3%BCcher.example"));
    }

    @Test
    void acceptsPublicIpv4AndIpv6Literals() {
        // 公网 IPv4/IPv6 字面量放行(走 canonical 字面量校验 + 解析地址公网校验)
        assertEquals(
                "https://8.8.8.8",
                OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://8.8.8.8").toString());
        assertEquals(
                "https://[2606:4700:4700::1111]",
                OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://[2606:4700:4700::1111]")
                        .toString());
    }

    @Test
    void rejectsLocalAndNonCanonicalAddressLiterals() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://localhost"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://127.0.0.1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://2130706433"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://0177.0.0.1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://10.0.0.1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://256.1.1.1")); // 八位组 >255
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl(
                        "https://99999999999999999999.1.1.1")); // parseInt 溢出
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://1.2.3")); // 非 4 段
        assertThrows(
                IllegalArgumentException.class, () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://[::1]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://[fe80::1%25eth0]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://[zz::1]")); // 非法 IPv6 字面量
    }

    @Test
    void rejectsMixedPublicAndPrivateDnsAnswers() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateResolvedAddresses(
                        List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1"))));
    }

    @Test
    void rejectsEmptyDnsAnswers() {
        assertThrows(IllegalArgumentException.class, () -> OutboundUrlPolicy.validateResolvedAddresses(List.of()));
        assertThrows(IllegalArgumentException.class, () -> OutboundUrlPolicy.validateResolvedAddresses(null));
    }

    @Test
    void acceptsOnlyPublicDnsAnswers() throws Exception {
        OutboundUrlPolicy.validateResolvedAddresses(
                List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1")));
    }

    @Test
    void rejectsReservedIpv4Ranges() throws Exception {
        // 私网/环回/链路本地/CGNAT/benchmark/multicast 全拒绝(防 DNS 答案指向内网)
        for (String ip : List.of(
                "0.0.0.1",
                "10.1.2.3",
                "100.64.0.1",
                "100.127.255.254",
                "169.254.1.1",
                "172.16.0.1",
                "172.31.255.254",
                "192.0.0.1",
                "192.168.1.1",
                "198.18.0.1",
                "198.19.1.1",
                "198.51.100.1",
                "203.0.113.1",
                "224.0.0.1",
                "255.255.255.255")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OutboundUrlPolicy.validateResolvedAddresses(List.of(InetAddress.getByName(ip))),
                    ip);
        }
    }

    @Test
    void ipv6UnicastClassification() throws Exception {
        // GUA 放行;documentation/teredo/6to4/link-local/mapped-private 拒绝
        assertTrue(OutboundUrlPolicy.isPublicUnicast(InetAddress.getByName("2606:4700:4700::1111")));
        assertFalse(OutboundUrlPolicy.isPublicUnicast(InetAddress.getByName("2001:db8::1"))); // documentation
        assertFalse(OutboundUrlPolicy.isPublicUnicast(InetAddress.getByName("2001::1"))); // teredo
        assertFalse(OutboundUrlPolicy.isPublicUnicast(InetAddress.getByName("2002::1"))); // 6to4
        assertFalse(OutboundUrlPolicy.isPublicUnicast(InetAddress.getByName("fe80::1"))); // link-local
        assertFalse(OutboundUrlPolicy.isPublicUnicast(InetAddress.getByName("::1"))); // loopback
        assertTrue(OutboundUrlPolicy.isPublicUnicast(InetAddress.getByName("8.8.8.8"))); // IPv4 公网
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateResolvedAddresses(List.of(InetAddress.getByName("2001:db8::1"))));
    }
}

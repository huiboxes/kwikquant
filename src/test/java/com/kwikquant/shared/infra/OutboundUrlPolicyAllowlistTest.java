package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OutboundUrlPolicy} self-host allowlist 豁免分支。
 * 全局静态态,用例内 configure + {@link #reset()} 清理,防跨用例泄漏(默认全禁)。
 */
class OutboundUrlPolicyAllowlistTest {

    @AfterEach
    void reset() {
        OutboundUrlPolicy.configureAllowedPrivateHosts(Set.of());
    }

    @Test
    void allowlistedLoopback_httpAllowed() {
        OutboundUrlPolicy.configureAllowedPrivateHosts(Set.of("localhost", "127.0.0.1", "::1"));

        URI uri = OutboundUrlPolicy.validateAndNormalizeBaseUrl("http://127.0.0.1:8901/v1");
        assertEquals("http", uri.getScheme());
        assertEquals(8901, uri.getPort());

        URI local = OutboundUrlPolicy.validateAndNormalizeBaseUrl("http://localhost:11434");
        assertEquals("localhost", local.getHost());
    }

    @Test
    void nonAllowlistedPrivate_stillRejected() {
        OutboundUrlPolicy.configureAllowedPrivateHosts(Set.of("localhost", "127.0.0.1", "::1"));

        // 不在 allowlist 的私网地址仍全禁
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("http://192.168.1.10"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("https://192.168.1.10"));
        // 公网 http 仍禁(豁免仅私网 allowlist)
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("http://example.com"));
    }

    @Test
    void defaultEmptyAllowlist_loopbackRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("http://127.0.0.1:8901"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateAndNormalizeBaseUrl("http://localhost:11434"));
    }

    @Test
    void allowlistedLoopbackIpv6_expandedFormAlsoAllowed() throws Exception {
        OutboundUrlPolicy.configureAllowedPrivateHosts(Set.of("localhost", "127.0.0.1", "::1"));

        // ::1 的展开形(0:0:0:0:0:0:0:1,getHostAddress 返回值)按字节相等命中 allowlist;
        // 双栈机 localhost 同时解析出两地址也整体放行(修复 IPv6 豁免失效)
        assertDoesNotThrow(
                () -> OutboundUrlPolicy.validateResolvedAddresses(List.of(InetAddress.getByName("0:0:0:0:0:0:0:1"))));
        assertDoesNotThrow(
                () -> OutboundUrlPolicy.validateResolvedAddresses(List.of(InetAddress.getByName("127.0.0.1"))));
        assertDoesNotThrow(() -> OutboundUrlPolicy.validateResolvedAddresses(
                List.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))));
    }

    @Test
    void resolvedPrivateNotInAllowlist_rejected() throws Exception {
        OutboundUrlPolicy.configureAllowedPrivateHosts(Set.of("127.0.0.1"));

        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundUrlPolicy.validateResolvedAddresses(List.of(InetAddress.getByName("192.168.1.10"))));
    }
}

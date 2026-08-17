package com.kwikquant.ai.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.DefaultEventLoop;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SafeAddressResolverGroup 单测:DNS 答案校验 + 地址钉扎。mock delegate resolver 返
 * 预置地址列表(公网/私网/失败),验 promise 完成语义:公网答案放行、私网答案拒绝
 * (SSRF 防线:防 DNS rebinding 后连内网)、上游失败透传。
 */
class SafeAddressResolverGroupTest {

    private static final DefaultEventLoop SHARED_EXECUTOR = new DefaultEventLoop();
    private final EventExecutor executor = SHARED_EXECUTOR;
    private AddressResolver<InetSocketAddress> delegateResolver;
    private SafeAddressResolverGroup group;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        delegateResolver = mock(AddressResolver.class);
        AddressResolverGroup<InetSocketAddress> delegateGroup = mock(AddressResolverGroup.class);
        when(delegateGroup.getResolver(any(EventExecutor.class))).thenReturn(delegateResolver);
        group = new SafeAddressResolverGroup(delegateGroup);
    }

    @AfterAll
    static void shutdownExecutor() {
        SHARED_EXECUTOR.shutdownGracefully();
    }

    /** 构造 delegate resolveAll 结果(已完成的 promise)。 */
    private void stubResolveAll(List<InetSocketAddress> addresses) {
        DefaultPromise<List<InetSocketAddress>> p = new DefaultPromise<>(executor);
        p.setSuccess(addresses);
        when(delegateResolver.resolveAll(any())).thenReturn(p);
    }

    private void stubResolveAllFailure(Throwable cause) {
        DefaultPromise<List<InetSocketAddress>> p = new DefaultPromise<>(executor);
        p.setFailure(cause);
        when(delegateResolver.resolveAll(any())).thenReturn(p);
    }

    private static InetSocketAddress addr(String ip) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(ip), 443);
    }

    @Test
    void resolve_returnsFirstPublicAddress() throws Exception {
        stubResolveAll(List.of(addr("8.8.8.8"), addr("1.1.1.1")));
        AddressResolver<InetSocketAddress> resolver = group.getResolver(executor);

        InetSocketAddress resolved = resolver.resolve(InetSocketAddress.createUnresolved("example.com", 443))
                .get();

        assertEquals(InetAddress.getByName("8.8.8.8"), resolved.getAddress());
    }

    @Test
    void resolveAll_returnsAllPublicAddresses() throws Exception {
        stubResolveAll(List.of(addr("8.8.8.8"), addr("1.1.1.1")));
        AddressResolver<InetSocketAddress> resolver = group.getResolver(executor);

        List<InetSocketAddress> resolved = resolver.resolveAll(InetSocketAddress.createUnresolved("example.com", 443))
                .get();

        assertEquals(2, resolved.size());
    }

    @Test
    void resolve_rejectsPrivateDnsAnswer() throws Exception {
        // 域名解析到私网 → promise 失败(SSRF 防线,防 DNS 答案指向内网)
        stubResolveAll(List.of(addr("10.0.0.1")));
        AddressResolver<InetSocketAddress> resolver = group.getResolver(executor);

        ExecutionException e = assertThrows(ExecutionException.class, () -> resolver.resolve(
                        InetSocketAddress.createUnresolved("evil.example", 443))
                .get());
        assertTrue(e.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void resolveAll_rejectsMixedAnswers() throws Exception {
        stubResolveAll(List.of(addr("8.8.8.8"), addr("169.254.1.1")));
        AddressResolver<InetSocketAddress> resolver = group.getResolver(executor);

        ExecutionException e = assertThrows(ExecutionException.class, () -> resolver.resolveAll(
                        InetSocketAddress.createUnresolved("evil.example", 443))
                .get());
        assertTrue(e.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void resolve_propagatesUpstreamFailure() {
        // DNS 本身失败(解析不出)→ 透传原异常
        RuntimeException dnsFailure = new RuntimeException("NXDOMAIN");
        stubResolveAllFailure(dnsFailure);
        AddressResolver<InetSocketAddress> resolver = group.getResolver(executor);

        ExecutionException e = assertThrows(ExecutionException.class, () -> resolver.resolve(
                        InetSocketAddress.createUnresolved("nope.example", 443))
                .get());
        assertEquals(dnsFailure, e.getCause());
    }

    @Test
    void delegatesSupportResolvedAndClose() {
        when(delegateResolver.isSupported(any())).thenReturn(true);
        when(delegateResolver.isResolved(any())).thenReturn(false);
        AddressResolver<InetSocketAddress> resolver = group.getResolver(executor);
        InetSocketAddress target = InetSocketAddress.createUnresolved("example.com", 443);

        assertTrue(resolver.isSupported(target));
        assertEquals(false, resolver.isResolved(target));
        resolver.close();
        verify(delegateResolver).close();
    }
}

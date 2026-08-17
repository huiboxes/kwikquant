package com.kwikquant.ai.infrastructure;

import com.kwikquant.shared.infra.OutboundUrlPolicy;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/** Validates the DNS answer and pins exactly those addresses for the outbound connection. */
final class SafeAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final AddressResolverGroup<InetSocketAddress> delegate;

    SafeAddressResolverGroup(AddressResolverGroup<InetSocketAddress> delegate) {
        this.delegate = delegate;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new SafeAddressResolver(delegate.getResolver(executor), executor);
    }

    private static final class SafeAddressResolver implements AddressResolver<InetSocketAddress> {
        private final AddressResolver<InetSocketAddress> delegate;
        private final EventExecutor executor;

        private SafeAddressResolver(AddressResolver<InetSocketAddress> delegate, EventExecutor executor) {
            this.delegate = delegate;
            this.executor = executor;
        }

        @Override
        public boolean isSupported(SocketAddress address) {
            return delegate.isSupported(address);
        }

        @Override
        public boolean isResolved(SocketAddress address) {
            return delegate.isResolved(address);
        }

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address) {
            return resolve(address, executor.newPromise());
        }

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address, Promise<InetSocketAddress> promise) {
            delegate.resolveAll(address).addListener(future -> completeOne(cast(future), promise));
            return promise;
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(SocketAddress address) {
            return resolveAll(address, executor.newPromise());
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(
                SocketAddress address, Promise<List<InetSocketAddress>> promise) {
            delegate.resolveAll(address).addListener(future -> completeAll(cast(future), promise));
            return promise;
        }

        @Override
        public void close() {
            delegate.close();
        }

        private static void completeOne(Future<List<InetSocketAddress>> future, Promise<InetSocketAddress> promise) {
            if (!future.isSuccess()) {
                promise.tryFailure(future.cause());
                return;
            }
            List<InetSocketAddress> addresses = future.getNow();
            try {
                validate(addresses);
                promise.trySuccess(addresses.get(0));
            } catch (RuntimeException e) {
                promise.tryFailure(e);
            }
        }

        private static void completeAll(
                Future<List<InetSocketAddress>> future, Promise<List<InetSocketAddress>> promise) {
            if (!future.isSuccess()) {
                promise.tryFailure(future.cause());
                return;
            }
            List<InetSocketAddress> addresses = future.getNow();
            try {
                validate(addresses);
                promise.trySuccess(List.copyOf(addresses));
            } catch (RuntimeException e) {
                promise.tryFailure(e);
            }
        }

        @SuppressWarnings("unchecked")
        private static Future<List<InetSocketAddress>> cast(Future<?> future) {
            return (Future<List<InetSocketAddress>>) future;
        }

        private static void validate(List<InetSocketAddress> addresses) {
            OutboundUrlPolicy.validateResolvedAddresses(
                    addresses.stream().map(InetSocketAddress::getAddress).toList());
        }
    }
}

package com.kwikquant.market.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.shared.infra.PortfolioSubscriptionRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

class WebSocketConfigTest {

    private static StompSubscriptionInterceptor subscriptionInterceptor() {
        return new StompSubscriptionInterceptor(
                mock(MarketDataService.class), mock(PortfolioSubscriptionRegistry.class));
    }

    @Test
    void configureMessageBroker_shouldRegisterTopicPrefix() {
        var config = new WebSocketConfig(mock(HandshakeInterceptor.class), subscriptionInterceptor(), List.of());
        var registry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    void registerStompEndpoints_shouldRegisterWsEndpoint() {
        var interceptor = mock(HandshakeInterceptor.class);
        var config = new WebSocketConfig(interceptor, subscriptionInterceptor(), List.of());
        // deep stubs：addEndpoint("/ws").addInterceptors(...) 链式返回
        var registry = mock(StompEndpointRegistry.class, withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS));

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
    }

    @Test
    void registerStompEndpoints_noOriginPatterns_keepsDefaultSameOriginOnly() {
        // 空白条目视同未配置(prod 不配 → 仅默认同源判定,不加白名单)
        var interceptor = mock(HandshakeInterceptor.class);
        var config = new WebSocketConfig(interceptor, subscriptionInterceptor(), List.of(" ", ""));
        var registry = mock(StompEndpointRegistry.class, withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS));

        config.registerStompEndpoints(registry);

        verify(registry.addEndpoint("/ws").addInterceptors(interceptor), never())
                .setAllowedOriginPatterns(any(String[].class));
    }

    @Test
    void registerStompEndpoints_originPatterns_appliesWhitelistSkippingBlanks() {
        var interceptor = mock(HandshakeInterceptor.class);
        var config = new WebSocketConfig(
                interceptor, subscriptionInterceptor(), List.of("http://localhost:*", "  ", "http://127.0.0.1:*"));
        var registry = mock(StompEndpointRegistry.class, withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS));

        config.registerStompEndpoints(registry);

        // 空白条目被过滤,仅两个有效 pattern 落到端点(dev 放行本机开发源)
        verify(registry.addEndpoint("/ws").addInterceptors(interceptor))
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }
}

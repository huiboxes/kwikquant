package com.kwikquant.market.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

class WebSocketConfigTest {

    @Test
    void configureMessageBroker_shouldRegisterTopicPrefix() {
        var config = new WebSocketConfig(mock(HandshakeInterceptor.class));
        var registry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    void registerStompEndpoints_shouldRegisterWsEndpoint() {
        var interceptor = mock(HandshakeInterceptor.class);
        var config = new WebSocketConfig(interceptor);
        // deep stubs：addEndpoint("/ws").addInterceptors(...).setAllowedOriginPatterns("*") 链式返回
        var registry = mock(StompEndpointRegistry.class, withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS));

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
    }
}

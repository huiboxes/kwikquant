package com.kwikquant.market.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * STOMP broker 配置。
 *
 * <p>注入 {@link HandshakeInterceptor} 接口（而非 account 模块的 WebSocketAuthInterceptor 具体类），
 * 避免 market → account 的 Spring Modulith 跨模块依赖。account 模块的 WebSocketAuthInterceptor
 * 已加 {@code @Component} 注册为 HandshakeInterceptor bean，此处自动注入。
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final HandshakeInterceptor authInterceptor;

    WebSocketConfig(HandshakeInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").addInterceptors(authInterceptor).setAllowedOriginPatterns("*");
    }
}

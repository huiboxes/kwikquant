package com.kwikquant.market.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
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
 *
 * <p>M5: {@link StompSubscriptionInterceptor} 注册在 clientInboundChannel，在 SUBSCRIBE 帧阶段
 * 校验订阅目标末尾 userId 与握手认证 userId 一致，防止跨用户监听
 * {@code /topic/orders/{userId}} 等用户专属主题（orders/fills/positions/notifications）。
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final HandshakeInterceptor authInterceptor;
    private final StompSubscriptionInterceptor subscriptionInterceptor;

    WebSocketConfig(HandshakeInterceptor authInterceptor, StompSubscriptionInterceptor subscriptionInterceptor) {
        this.authInterceptor = authInterceptor;
        this.subscriptionInterceptor = subscriptionInterceptor;
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

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionInterceptor);
    }
}

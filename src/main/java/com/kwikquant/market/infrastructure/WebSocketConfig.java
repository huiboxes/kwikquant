package com.kwikquant.market.infrastructure;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * STOMP broker 配置。
 *
 * <p>注入 {@link HandshakeInterceptor} 接口（而非 account 模块的 WebSocketAuthInterceptor 具体类），
 * 避免 market → account 的 Spring Modulith 跨模块依赖。account 模块的 WebSocketAuthInterceptor
 * 已加 {@code @Component} 注册为 HandshakeInterceptor bean，此处自动注入。
 *
 * <p>{@link StompSubscriptionInterceptor} 注册在 clientInboundChannel，在 SUBSCRIBE 帧阶段
 * 校验订阅目标末尾 userId 与握手认证 userId 一致，防止跨用户监听
 * {@code /topic/orders/{userId}} 等用户专属主题（orders/fills/positions/notifications）。
 *
 * <p><b>Origin 策略</b>：Spring 握手默认仅同源（Origin 对 Host）放行。dev 页面源（vite 5173）≠
 * 后端 Host（8080），跨源握手被拒 403，故 {@code kwikquant.ws.allowed-origin-patterns} 白名单
 * 放行开发源（application-dev.yaml）；prod 同源部署不配置，保持默认同源判定。
 * 同源请求恒放行（OriginHandshakeInterceptor 先判同源再查白名单），不影响 prod 的同源判定。
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final HandshakeInterceptor authInterceptor;
    private final StompSubscriptionInterceptor subscriptionInterceptor;
    private final List<String> allowedOriginPatterns;

    WebSocketConfig(
            HandshakeInterceptor authInterceptor,
            StompSubscriptionInterceptor subscriptionInterceptor,
            @Value("${kwikquant.ws.allowed-origin-patterns:}") List<String> allowedOriginPatterns) {
        this.authInterceptor = authInterceptor;
        this.subscriptionInterceptor = subscriptionInterceptor;
        this.allowedOriginPatterns = allowedOriginPatterns.stream()
                .filter(p -> p != null && !p.isBlank())
                .toList();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        StompWebSocketEndpointRegistration endpoint =
                registry.addEndpoint("/ws").addInterceptors(authInterceptor);
        if (!allowedOriginPatterns.isEmpty()) {
            endpoint.setAllowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new));
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionInterceptor);
    }
}

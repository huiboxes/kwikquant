package com.kwikquant.ai.infrastructure;

import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * LLM adapter 共享 WebClient bean（替代各 adapter 各自 {@code WebClient.builder().build()}）。
 *
 * <p>connect timeout 10s 防连接阶段挂死；response timeout 60s 作为 SSE 流式整体上限（单帧间隔兜底，
 * 与 pipeline 内的 3min {@code .timeout} 互补：前者断 provider 200 OK 后不发首 chunk 吊死，
 * 后者断单帧间隔超 3min）。按类型注入到 3 个 adapter（全仓唯一 WebClient bean，无歧义）。
 */
@Configuration
class LlmWebClientConfig {

    @Bean
    WebClient llmWebClient() {
        HttpClient httpClient = HttpClient.create()
                .resolver(new SafeAddressResolverGroup(DefaultAddressResolverGroup.INSTANCE))
                .followRedirect(false)
                .responseTimeout(Duration.ofSeconds(60))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}

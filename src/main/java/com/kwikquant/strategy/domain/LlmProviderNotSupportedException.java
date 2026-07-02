package com.kwikquant.strategy.domain;

import com.kwikquant.shared.types.LlmProvider;

/**
 * LLM provider 未被服务端适配器注册。属于服务端配置问题（应用未装载该 provider 的 adapter bean），
 * 不是用户输入错误，映射为 {@code LLM_KEY_INVALID_PROVIDER 8002 + 500}。
 */
public class LlmProviderNotSupportedException extends RuntimeException {

    private final LlmProvider provider;

    public LlmProviderNotSupportedException(LlmProvider provider) {
        super("Unsupported provider: " + provider);
        this.provider = provider;
    }

    public LlmProvider provider() {
        return provider;
    }
}

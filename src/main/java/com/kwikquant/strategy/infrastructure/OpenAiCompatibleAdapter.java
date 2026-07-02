package com.kwikquant.strategy.infrastructure;

import com.kwikquant.shared.types.LlmProvider;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容协议 adapter（DeepSeek / 通义 / 本地 vLLM 等）。baseUrl 必须来自用户配置的 LlmApiKey.baseUrl
 * （LlmApiKeyService.create 已校验 OPENAI_COMPATIBLE 必填 baseUrl）。
 */
@Component
class OpenAiCompatibleAdapter extends AbstractOpenAiAdapter {

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPENAI_COMPATIBLE;
    }

    @Override
    protected String defaultBaseUrl() {
        // 必须来自 request.baseUrl()（用户配置），基类 stream() 在 baseUrl 为 null 时报错
        return null;
    }

    @Override
    protected String defaultModel() {
        // OPENAI_COMPATIBLE（DeepSeek/通义等）无统一默认模型，必须由用户传入 model；
        // 返回 null，AbstractOpenAiAdapter.stream() 在 model 为 null 时报错。
        return null;
    }
}

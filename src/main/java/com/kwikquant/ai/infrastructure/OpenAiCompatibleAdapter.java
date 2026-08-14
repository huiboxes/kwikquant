package com.kwikquant.ai.infrastructure;

import com.kwikquant.ai.application.LlmProperties;
import com.kwikquant.shared.types.LlmProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * OpenAI 兼容协议 adapter（DeepSeek / 通义 / 本地 vLLM 等）。baseUrl 必须来自用户配置的 LlmApiKey.baseUrl
 * （LlmApiKeyService.create 已校验 OPENAI_COMPATIBLE 必填 baseUrl）；model 必须由用户传入
 * （properties 无 COMPATIBLE→defaultModel() 返 null→stream() 报 model required）。
 */
@Component
class OpenAiCompatibleAdapter extends AbstractOpenAiAdapter {

    OpenAiCompatibleAdapter(WebClient llmWebClient, LlmProperties llmProperties) {
        super(llmWebClient, llmProperties);
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPENAI_COMPATIBLE;
    }

    @Override
    protected String defaultBaseUrl() {
        // 必须来自 request.baseUrl()（用户配置），AbstractLlmAdapter.streamSse 在 baseUrl 为 null 时报错
        return null;
    }
}

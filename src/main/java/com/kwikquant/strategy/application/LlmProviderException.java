package com.kwikquant.strategy.application;

/**
 * LLM Provider 调用异常。adapter 捕获 {@code WebClientResponseException} 包装为此异常（含 HTTP 状态码），
 * AiChatService 按 {@link #httpStatus()} 分类脱敏（spec-review S-5，不透传 provider 原始错误）。
 */
public class LlmProviderException extends RuntimeException {

    private final int httpStatus;

    public LlmProviderException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}

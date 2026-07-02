package com.kwikquant.strategy.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * AI 对话消息。role 限 system/user/assistant（spec-review S-2 输入校验）。
 */
public record ChatMessage(
        @NotBlank @Pattern(regexp = "^(system|user|assistant)$", message = "role must be system/user/assistant")
                String role,
        @NotBlank @Size(max = 100_000, message = "content too long") String content) {}

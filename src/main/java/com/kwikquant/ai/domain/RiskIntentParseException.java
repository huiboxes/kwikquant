package com.kwikquant.ai.domain;

/**
 * 自然语言风控解析失败:LLM 输出为空/非 JSON/提取不出任何合法规则。
 *
 * <p>属用户输入语义问题(描述未包含可识别的风控规则),非 provider 故障(那走
 * {@link com.kwikquant.ai.application.LlmProviderException} → 8003)。由
 * {@code AiExceptionHandler} 映射 400 + {@code ErrorCode.AI_PARSE_FAILED}(8004),
 * 响应文案固定不透传 LLM 原始输出;构造入参 reason 仅供服务端日志定位。
 */
public class RiskIntentParseException extends RuntimeException {

    public RiskIntentParseException(String reason) {
        super(reason);
    }
}

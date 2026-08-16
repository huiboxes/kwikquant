package com.kwikquant.ai.application;

/**
 * Usage token 累加回调。adapter 从 SSE usage 帧提取到 token 数后调 {@link #accept},
 * 调用方({@code AiChatService})传可变实现累加,流终止时落库。{@link #noop()} 提供 no-op 实现
 * 供测试/不关心 usage 的调用方使用。
 *
 * <p>函数式接口,adapter extract 到 {@link Usage} 后调 sink,把 usage 副产物与 content 流解耦:
 * content 主流程不被 usage 解析异常中断(doOnNext 内 try-catch)。
 */
@FunctionalInterface
public interface UsageSink {

    void accept(int promptTokens, int completionTokens);

    /** No-op sink:丢弃所有 usage(测试/不关心 usage 的调用方)。 */
    static UsageSink noop() {
        return (p, c) -> {};
    }
}

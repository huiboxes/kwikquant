package com.kwikquant.ai.application;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可变 usage sink:累加 doOnNext 提取到的 token 数。{@link AtomicInteger} 保线程安全——
 * reactive 的 doOnNext 与流终止的 doFinally 可能在不同 reactor 线程执行(sink.accept 累加 +
 * sink.promptTokens 读取跨线程)。忽略 ≤0 项(OpenAI 末帧同时含 prompt+completion;Anthropic
 * 跨两帧,每次只传一项、另一项为 0)。
 *
 * <p>同步非流式调用(summarize/testConnection/自然语言风控解析)与 SSE 主流共用。
 */
final class MutableUsageSink implements UsageSink {
    private final AtomicInteger prompt = new AtomicInteger();
    private final AtomicInteger completion = new AtomicInteger();

    @Override
    public void accept(int p, int c) {
        if (p > 0) {
            prompt.addAndGet(p);
        }
        if (c > 0) {
            completion.addAndGet(c);
        }
    }

    int promptTokens() {
        return prompt.get();
    }

    int completionTokens() {
        return completion.get();
    }
}

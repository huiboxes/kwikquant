package com.kwikquant.ai.application;

import com.kwikquant.ai.domain.AiUsageLog;
import com.kwikquant.ai.domain.AiUsageSource;
import com.kwikquant.ai.infrastructure.AiUsageLogMapper;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * AI 调用 usage 落库服务。{@code @Async} 派发到 {@code taskExecutor}(裸 @Async 默认 bean,见
 * {@link com.kwikquant.shared.infra.AsyncConfig}),不阻塞 SSE 主流程;reactive 流 doFinally/
 * 同步调用方调本方法,失败仅 warn 不影响业务(usage 是计费副产物,非业务关键)。
 *
 * <p>两道 try-catch 兜底:外层(调用方 doFinally/testConnection/summarize)防 {@code taskExecutor}
 * 拒绝({@code RejectedExecutionException})传播到 reactive 流中断 SSE;内层(本方法)防 DB 异常
 * (唯一约束/连接断开)影响异步线程。无 usage(prompt/completion 都 ≤0)不记(防 0/0 噪声行)。
 */
@Service
public class AiUsageLogService {

    private final AiUsageLogMapper mapper;

    public AiUsageLogService(AiUsageLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @Async 落库 usage。无 usage(prompt/completion 都 ≤0)不记(防 0/0 噪声行);DB 异常仅 warn
     * 不抛(usage 非业务关键)。{@code source} 取 {@link AiUsageSource#name()} 小写存
     * ({@code chat}/{@code summary}/{@code test})。
     */
    @Async
    public void log(
            long userId, long keyId, String model, int promptTokens, int completionTokens, AiUsageSource source) {
        if (promptTokens <= 0 && completionTokens <= 0) {
            return; // 无 usage 不记(防 0/0 噪声行)
        }
        AiUsageLog l = new AiUsageLog();
        l.setUserId(userId);
        l.setKeyId(keyId);
        l.setModel(model);
        l.setPromptTokens(promptTokens);
        l.setCompletionTokens(completionTokens);
        l.setSource(source.name().toLowerCase()); // chat/summary/test
        try {
            mapper.insert(l);
        } catch (Exception e) {
            // usage 落库失败不影响主流程(纯计费,非业务关键),仅 warn。
            LoggerFactory.getLogger(AiUsageLogService.class).warn("ai usage log insert failed", e);
        }
    }
}

package com.kwikquant.ai.application;

import com.kwikquant.shared.types.LlmProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 上下文窗口管理器：历史超预算时摘要历史（除 system + 最近 N 条），摘要失败兜底截断最旧，
 * 不阻塞会话。把原 AiChatService 内联的压缩逻辑独立成组件。
 *
 * <p><b>预算解析</b>：{@code budget = resolveWindow(model) - REPLY_RESERVE}；window 按模型名匹配
 * {@link ContextWindowProperties#byModel()}（contains），命中取配置值，否则取 {@code defaultTokens}。
 *
 * <p><b>Anthropic 归整</b>：provider=={@link LlmProvider#ANTHROPIC} 时保证 system 之后 user/assistant
 * 严格交替、首条非 system 为 user；OPENAI/COMPATIBLE 跳过（provider 对消息顺序无此约束）。
 *
 * <p><b>压缩结果</b>：{@link CompressionResult#summary()} 非空表示发生了摘要（落库为合成 assistant 消息，
 * 由 AiChatService 负责）；为 null 表示未压缩或摘要失败兜底（不落库）。
 */
@Component
public class ContextWindowManager {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    /** 压缩时保留最近 N 条（3 轮 user+assistant），其余历史摘要替换。 */
    static final int COMPRESS_KEEP_RECENT = 6;

    /** 回复预留 token（max_tokens 预留 + 输出缓冲），不计入历史预算。 */
    static final int REPLY_RESERVE = 4096;

    /** 摘要请求自身超限防护：按字符数粗截到 ~50% 窗口再送 summarizer。 */
    private static final double COMPRESS_CHAR_RATIO = 0.5;

    private final TokenEstimator estimator;
    private final ContextWindowProperties properties;

    public ContextWindowManager(TokenEstimator estimator, ContextWindowProperties properties) {
        this.estimator = estimator;
        this.properties = properties;
    }

    /** 压缩结果：{@code messages} 为送 LLM 的最终消息列表；{@code summary} 非空=已摘要（落库用）。 */
    public record CompressionResult(List<ChatMessage> messages, String summary) {}

    /**
     * 按预算压缩历史。summarizer 由调用方传入（AiChatService 用 adapter.stream().reduce().block() 闭包），
     * 抛异常=摘要失败→走兜底截断。
     */
    public CompressionResult compress(
            List<ChatMessage> messages,
            LlmProvider provider,
            String model,
            Function<List<ChatMessage>, String> summarizer) {
        return compress(messages, provider, model, REPLY_RESERVE, summarizer);
    }

    public CompressionResult compress(
            List<ChatMessage> messages,
            LlmProvider provider,
            String model,
            int maxTokens,
            Function<List<ChatMessage>, String> summarizer) {
        int budget = resolveWindow(model) - maxTokens;
        if (maxTokens <= 0 || budget <= 0) {
            throw new IllegalArgumentException("maxTokens must be smaller than the model context window");
        }
        // 2. system 位置：messages[0] 为 system 则压缩全程不碰它。
        int systemIdx = (!messages.isEmpty() && "system".equals(messages.get(0).role())) ? 1 : 0;
        // 3. 估算 ≤ 预算 → 不动，直接返（null summary）。
        if (estimator.estimate(messages) <= budget) {
            return new CompressionResult(messages, null);
        }
        // 4. 压缩区右界：保留最近 COMPRESS_KEEP_RECENT 条，其余摘要。太少（compressEnd ≤ systemIdx）不压缩。
        int compressEnd = messages.size() - COMPRESS_KEEP_RECENT;
        if (compressEnd <= systemIdx) {
            return hardGate(messages, systemIdx, budget, null);
        }
        List<ChatMessage> toCompress = new ArrayList<>(messages.subList(systemIdx, compressEnd));
        // 5. 先截到 ~50% 窗口（按字符数粗截，保留每条完整性，丢最旧到 ≤ window*0.5），防摘要请求自身超限。
        List<ChatMessage> toCompressTruncated = truncateByChars(toCompress, resolveWindow(model));
        // 6. 摘要；抛异常或返空 → 兜底截最旧（保留 system + 最近 N 起的部分，按预算硬丢），summary=null 不落库。
        String summary;
        try {
            summary = summarizer.apply(toCompressTruncated);
        } catch (Exception e) {
            log.warn("history compression failed, fallback to truncate oldest", e);
            return fallbackTruncate(messages, systemIdx, compressEnd, budget);
        }
        if (summary == null || summary.isBlank()) {
            // 摘要为空同样视为失败（provider 返空流时避免注入无内容摘要），走兜底截断。
            log.warn("history compression returned empty summary, fallback to truncate oldest");
            return fallbackTruncate(messages, systemIdx, compressEnd, budget);
        }
        // 7. 成功：system? + summary-assistant + 最近 N。
        List<ChatMessage> compressed = new ArrayList<>();
        if (systemIdx == 1) {
            compressed.add(messages.get(0));
        }
        compressed.add(new ChatMessage("assistant", "（对话摘要）" + summary));
        compressed.addAll(messages.subList(compressEnd, messages.size()));
        // 8. Anthropic 归整：system 之后 user/assistant 严格交替、首条非 system 为 user。OPENAI/COMPATIBLE 跳过。
        if (provider == LlmProvider.ANTHROPIC) {
            compressed = normalizeForAnthropic(compressed, systemIdx);
        }
        // 9. 硬预算闸：仍超预算则从 system 之后第一条起丢（保 system + 末条），直到 ≤ 预算或只剩 system+1。
        return hardGate(compressed, systemIdx, budget, summary);
    }

    /** 解析模型窗口：model 名 contains byModel 某 key → 该值；否则 defaultTokens。 */
    private int resolveWindow(String model) {
        if (model != null) {
            Map<String, Integer> byModel = properties.byModel();
            for (Map.Entry<String, Integer> e : byModel.entrySet()) {
                if (model.contains(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        return properties.defaultTokens();
    }

    /**
     * 按字符数粗截待摘要列表：从最旧丢起，保留每条完整性，到总字符数 ≤ window*{@link #COMPRESS_CHAR_RATIO}。
     * 防摘要请求自身超 provider 窗口（摘要 prompt = 拼接的历史全文）。
     */
    private List<ChatMessage> truncateByChars(List<ChatMessage> toCompress, int window) {
        int charBudget = (int) (window * COMPRESS_CHAR_RATIO);
        int chars = 0;
        int keepFrom = 0;
        for (int i = toCompress.size() - 1; i >= 0; i--) {
            int len = toCompress.get(i).content() == null
                    ? 0
                    : toCompress.get(i).content().length();
            if (chars + len > charBudget) {
                keepFrom = i + 1;
                break;
            }
            chars += len;
        }
        return new ArrayList<>(toCompress.subList(keepFrom, toCompress.size()));
    }

    /**
     * 摘要失败兜底：[system?] + messages.subList(max(systemIdx, compressEnd), size)，
     * 再按预算从最旧非 system 丢到 ≤ budget（硬闸）。summary=null（不落库）。
     */
    private CompressionResult fallbackTruncate(List<ChatMessage> messages, int systemIdx, int compressEnd, int budget) {
        List<ChatMessage> truncated = new ArrayList<>();
        if (systemIdx == 1) {
            truncated.add(messages.get(0));
        }
        int start = Math.max(systemIdx, compressEnd);
        truncated.addAll(messages.subList(start, messages.size()));
        return hardGate(truncated, systemIdx, budget, null);
    }

    private CompressionResult hardGate(List<ChatMessage> messages, int systemIdx, int budget, String summary) {
        List<ChatMessage> gated = new ArrayList<>(messages);
        while (estimator.estimate(gated) > budget && gated.size() > systemIdx + 1) {
            gated.remove(systemIdx);
        }
        if (estimator.estimate(gated) > budget) {
            throw new IllegalArgumentException("system prompt and latest message exceed the model context window");
        }
        String marker = summary == null ? null : "（对话摘要）" + summary;
        boolean retained = marker != null
                && gated.stream()
                        .anyMatch(m -> m.content() != null && m.content().contains(marker));
        return new CompressionResult(gated, retained ? summary : null);
    }

    /**
     * Anthropic 归整：system 保留，之后连续同 role 用 "\n\n" 拼接（保留每条完整性），
     * 空 content 跳过；归整后首条非 system 若为 assistant → 前插 user "(continue)" 占位
     * （Anthropic 要求 system 之后首条为 user）。
     */
    private List<ChatMessage> normalizeForAnthropic(List<ChatMessage> compressed, int systemIdx) {
        List<ChatMessage> result = new ArrayList<>();
        // system 原样保留
        for (int i = 0; i < systemIdx && i < compressed.size(); i++) {
            result.add(compressed.get(i));
        }
        // 归整 system 之后：连续同 role 拼接，空 content 跳过
        List<ChatMessage> tail = compressed.subList(systemIdx, compressed.size());
        String pendingRole = null;
        StringBuilder pendingContent = new StringBuilder();
        for (ChatMessage m : tail) {
            if (m.content() == null || m.content().isBlank()) {
                continue;
            }
            if (m.role().equals(pendingRole)) {
                pendingContent.append("\n\n").append(m.content());
            } else {
                if (pendingRole != null) {
                    result.add(new ChatMessage(pendingRole, pendingContent.toString()));
                }
                pendingRole = m.role();
                pendingContent = new StringBuilder(m.content());
            }
        }
        if (pendingRole != null) {
            result.add(new ChatMessage(pendingRole, pendingContent.toString()));
        }
        // 首条非 system 为 assistant → 前插 user 占位（保严格交替、user 起首）
        if (systemIdx < result.size()
                && "assistant".equals(result.get(systemIdx).role())) {
            result.add(systemIdx, new ChatMessage("user", "(continue)"));
        }
        return result;
    }
}

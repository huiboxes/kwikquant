package com.kwikquant.strategy.application;

import com.kwikquant.account.application.LlmApiKeyService;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.shared.types.LlmProvider;
import com.kwikquant.strategy.domain.LlmProviderNotSupportedException;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * AI Chat 服务：接收用户消息 → 注入策略上下文 system prompt → 用用户自己的 LLM key 转发到 provider → SSE 流式返回。
 *
 * <p><b>Provider 适配</b>：通过 {@code List<LlmProviderAdapter>} 注入，按 {@code provider()} 索引到 EnumMap
 * （与 NotificationChannel 模式一致）。
 *
 * <p><b>SSE 错误脱敏（S-5）</b>：adapter 抛 {@link LlmProviderException} 时，按 {@link #sanitize(Throwable)}
 * 分类脱敏（401/403→"API key invalid or expired"；429→"Rate limit exceeded"；500+→"LLM provider service
 * unavailable"），不透传 provider 原始错误（避免泄露 OPENAI_COMPATIBLE 自定义 baseUrl/账户片段）。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final LlmApiKeyService keyService;
    private final StrategyCrudService crudService;
    private final StrategyCodeService codeService;
    private final Map<LlmProvider, LlmProviderAdapter> adapters;

    public AiChatService(
            LlmApiKeyService keyService,
            StrategyCrudService crudService,
            StrategyCodeService codeService,
            List<LlmProviderAdapter> adapterList) {
        this.keyService = keyService;
        this.crudService = crudService;
        this.codeService = codeService;
        this.adapters = new EnumMap<>(LlmProvider.class);
        for (LlmProviderAdapter a : adapterList) {
            this.adapters.put(a.provider(), a);
        }
    }

    public Flux<ServerSentEvent<String>> chat(AiChatRequest request, long userId) {
        LlmApiKey key = keyService.getOwned(request.llmKeyId(), userId);
        LlmProviderAdapter adapter = adapters.get(key.getProvider());
        if (adapter == null) {
            // 服务端配置错误（adapter bean 未注入），非用户参数错误 → 走专属 8002 而非 3001 VALIDATION_FAILED
            throw new LlmProviderNotSupportedException(key.getProvider());
        }
        String apiSecret = keyService.decryptSecret(key);
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        if (request.strategyId() != null) {
            StrategyDefinition s = crudService.getOwned(request.strategyId(), userId);
            String sourceCode = request.sourceCode();
            // M5 混合方案:EDITOR 模式前端传 sourceCode 直接用;DRAFT/PUBLISHED 后端注入
            // (省 1MB body + 后端可信 audit)。sourceCode==null 兜底进 switch(EDITOR+null 走 case EDITOR 返 null,
            // buildSystemPrompt 内 null 防御拼空串,不 NPE)。
            if (sourceCode == null || request.codeSource() != CodeSource.EDITOR) {
                sourceCode = switch (request.codeSource()) {
                    case DRAFT -> codeService
                            .getDraftCodeOwned(request.strategyId(), userId)
                            .getSourceCode();
                    case PUBLISHED -> codeService
                            .getPublishedCodeOwned(request.strategyId(), userId)
                            .getSourceCode();
                    case EDITOR -> request.sourceCode(); // 理论分支:editor 应传 sourceCode,此为兜底
                };
            }
            messages.add(0, new ChatMessage("system", buildSystemPrompt(s, sourceCode)));
        }
        // v2 model 优先级(tech-design §2.3):request.model()(会话级,空串视同未传,防前端误传 "" 导致
        // adapter 用 "" 当 model 名发 provider 报 "model not found" 而非 fallback) > keyService.defaultModelOf(key)
        // (available_models 首项或 null) > adapter.defaultModel()(provider 内置;COMPATIBLE null → Flux.error(0))。
        // adapter.defaultModel() 是 AbstractOpenAiAdapter protected,跨模块不可直调;此处前两级解析后传给
        // LlmStreamRequest,adapter 内部 `request.model() != null ? request.model() : defaultModel()` 兜底。
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model()
                : keyService.defaultModelOf(key);
        // Task 6 §6.2:压缩上下文(P1)—messages 超 30K token 摘要历史(除 system + 最近 6),
        // 摘要 LLM 调用失败兜底截断最旧,不阻塞会话
        messages = compressHistoryIfNeeded(adapter, apiSecret, key.getBaseUrl(), model, messages);
        LlmStreamRequest streamReq = new LlmStreamRequest(
                apiSecret,
                key.getBaseUrl(),
                model,
                messages,
                request.temperatureOrDefault(),
                request.maxTokensOrDefault());
        return adapter.stream(streamReq)
                .map(delta -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(delta)
                        .build())
                .onErrorResume(e -> {
                    // S-5: 原始 provider 错误仅记日志（不透传给用户），脱敏后发 SSE error event。
                    // 不落 e.getMessage()：provider 错误 body 可能 echo 请求（含用户误粘的 key/敏感字段）
                    // 或返回 header 片段，落日志即固化。仅记状态码 + 已脱敏分类。
                    if (e instanceof LlmProviderException lpe) {
                        log.warn("LLM provider error: status={}, category={}", lpe.httpStatus(), sanitize(e));
                    } else {
                        // 内部 bug(NPE/reactor 异常,非 provider 错误):打完整堆栈定位。
                        // 不涉及 provider body(那是 LlmProviderException 分支),S-5 脱敏不受影响。
                        log.warn("LLM stream interrupted", e);
                    }
                    return Flux.just(sseError(sanitize(e)));
                })
                .concatWith(Flux.just(sseDone()));
    }

    /**
     * 测连通性(tech-design §2.4):用 key+model 发最小 ping(messages=[hi], max_tokens=1),取首帧即返。
     * 10s 超时兜底(防 provider 200 OK 后不发首 chunk 吊死 servlet 线程)。异常复用 {@link #sanitize(Throwable)}
     * 脱敏,不透传 provider 原始 body(可能 echo 请求含 key)。挂在 strategy 模块(adapters/sanitize 同类可达)。
     */
    public LlmConnectionTestResult testConnection(long keyId, String model, long userId) {
        LlmApiKey key = keyService.getOwned(keyId, userId);
        LlmProviderAdapter adapter = adapters.get(key.getProvider());
        if (adapter == null) {
            throw new LlmProviderNotSupportedException(key.getProvider());
        }
        String apiSecret = keyService.decryptSecret(key);
        LlmStreamRequest req = new LlmStreamRequest(
                apiSecret, key.getBaseUrl(), model, List.of(new ChatMessage("user", "hi")), 0.0, 1);
        try {
            adapter.stream(req).next().timeout(Duration.ofSeconds(10)).block();
            return new LlmConnectionTestResult(true, "ok");
        } catch (LlmProviderException e) {
            return new LlmConnectionTestResult(false, sanitize(e));
        } catch (Exception e) {
            return new LlmConnectionTestResult(false, "Stream interrupted");
        }
    }

    /** 测连通性结果(tech-design §2.4);success=true 表示 key+model 可用,false 时 message 为脱敏文案。 */
    public record LlmConnectionTestResult(boolean success, String message) {}

    private static ServerSentEvent<String> sseError(String msg) {
        return ServerSentEvent.<String>builder().event("error").data(msg).build();
    }

    /**
     * 终止帧（契约改动 E）：Flux 正常结束发 {@code event: done}，让前端区分"正常结束"vs"网络断连"
     * （无 done 时前端只能靠 idle 超时兜底判异常）。error 路径经 onErrorResume 后也会 concat 此帧。
     */
    private static ServerSentEvent<String> sseDone() {
        return ServerSentEvent.<String>builder().event("done").data("[DONE]").build();
    }

    /**
     * tech-design §4.2 错误脱敏分类。按 {@link LlmProviderException#httpStatus()} 分 7 档,覆盖 status=0/-1
     * (B 层新增:adapter model 缺失 / 网络层包装)+ 401|403 / 429 / >=500 / 非标准 4xx 通用兜底。非
     * {@link LlmProviderException} 的 reactor 内部异常仍走 fallback "Stream interrupted"。
     *
     * <p>不透传 provider 原始 body:可能 echo 请求(含用户误粘的 key)或返回 header 片段,落 SSE 即固化到前端。
     */
    static String sanitize(Throwable e) {
        if (e instanceof LlmProviderException lpe) {
            int s = lpe.httpStatus();
            if (s == 0) {
                // adapter 检测到 model 缺失(AbstractOpenAiAdapter.stream):COMPATIBLE 无统一默认 model,
                // 给可操作文案引导用户在设置页为 LLM Key 配 model(tech-design §1 根因)。
                return "模型未指定,请在会话栏选择模型";
            }
            if (s == -1) {
                // adapter 把 WebClientRequestException(网络层:连接超时/被墙/DNS 失败)包装成 status=-1
                // (tech-design §4.1,本次新增),给可操作文案引导检查网络/代理/baseUrl。
                return "无法连接 LLM provider,请检查网络/代理/baseUrl";
            }
            if (s == 401 || s == 403) {
                return "API key invalid or expired";
            }
            if (s == 429) {
                return "Rate limit exceeded, please retry later";
            }
            if (s >= 500) {
                return "LLM provider service unavailable";
            }
            // 非标准 4xx(404 模型名错 / 400 messages 格式错等):透传状态码助排错,但不透传 body
            return "LLM provider 返回错误(状态码 " + s + ",可能模型名无效)";
        }
        return "Stream interrupted";
    }

    /** 截断阈值(字符数):~20K tokens,留余量给历史 + 元信息(spec §6.1 P0)。 */
    private static final int MAX_SOURCE_CHARS = 80_000;

    /**
     * 拼装 system prompt:角色定位 + 策略元信息(name/symbol/exchange/interval/parameters)+ sourceCode 代码块 + 指令。
     *
     * <p><b>截断兜底(§6.1 P0)</b>:service 内构造的 system message 不经 {@code @Size} 校验,可能超
     * {@code ChatMessage.content @Size(max=100_000)}。按字符数粗算截断(8 万字符),截断时拼提示行。
     *
     * <p><b>null 防御</b>:EDITOR 模式前端违规未传 sourceCode 时({@code @Size} 不强制 {@code @NotNull}),
     * 拼空串而非 "null" 字面量(LLM 见空代码块提示无代码,优于误导)。
     */
    private static String buildSystemPrompt(StrategyDefinition s, String sourceCode) {
        String safeCode = sourceCode == null ? "" : sourceCode;
        String truncated = safeCode.length() > MAX_SOURCE_CHARS
                ? safeCode.substring(0, MAX_SOURCE_CHARS) + "\n// ... code truncated (exceeds " + MAX_SOURCE_CHARS
                        + " chars) ..."
                : safeCode;
        return "You are assisting with a trading strategy. Name: "
                + s.getName() + ", symbol: " + s.getSymbol() + ", exchange: " + s.getExchange()
                + ", interval: " + s.getIntervalValue() + ", parameters: " + s.getParameters()
                + ".\n\nStrategy source code:\n```python\n" + truncated
                + "\n```\n\nHelp the user optimize or debug this strategy.";
    }

    // ---------- Task 6 §6.2: 压缩上下文(P1) ----------

    /** 历史压缩阈值(粗算 token,字符数/4);超此触发摘要(spec §6.2,30K 起步)。 */
    private static final int MAX_HISTORY_TOKENS = 30_000;

    /** 压缩时保留最近 N 条(3 轮 user+assistant),其余历史摘要替换。 */
    private static final int COMPRESS_KEEP_RECENT = 6;

    /** 摘要请求 max_tokens(spec §6.2)。 */
    private static final int SUMMARY_MAX_TOKENS = 500;

    /** 摘要 LLM 调用超时(防 provider 吊死 servlet 线程,testConnection 同款 30s)。 */
    private static final java.time.Duration SUMMARY_TIMEOUT = java.time.Duration.ofSeconds(30);

    /**
     * 压缩上下文(P1,spec §6.2):messages 粗算 token 超 {@link #MAX_HISTORY_TOKENS} 时,
     * 摘要历史(除 system + 最近 {@link #COMPRESS_KEEP_RECENT} 条)→ 摘要文本替换历史。
     * 摘要 LLM 调用失败兜底截断最旧(保留 system + 最近 N),不阻塞会话。
     */
    private List<ChatMessage> compressHistoryIfNeeded(
            LlmProviderAdapter adapter, String apiSecret, String baseUrl, String model, List<ChatMessage> messages) {
        if (estimateTokens(messages) <= MAX_HISTORY_TOKENS) return messages;
        int systemIdx = (!messages.isEmpty() && "system".equals(messages.get(0).role())) ? 1 : 0;
        int compressEnd = messages.size() - COMPRESS_KEEP_RECENT;
        if (compressEnd <= systemIdx) return messages; // 太少不压缩
        List<ChatMessage> toCompress = new ArrayList<>(messages.subList(systemIdx, compressEnd));
        try {
            String summary = summarize(adapter, apiSecret, baseUrl, model, toCompress);
            List<ChatMessage> compressed = new ArrayList<>();
            if (systemIdx == 1) compressed.add(messages.get(0)); // 保留 system
            compressed.add(new ChatMessage("assistant", "（对话摘要）" + summary));
            compressed.addAll(messages.subList(compressEnd, messages.size())); // 最近 N
            return compressed;
        } catch (Exception e) {
            log.warn("history compression failed, fallback to truncate oldest", e);
            List<ChatMessage> truncated = new ArrayList<>();
            if (systemIdx == 1) truncated.add(messages.get(0));
            int start = Math.max(systemIdx, compressEnd);
            truncated.addAll(messages.subList(start, messages.size()));
            return truncated;
        }
    }

    /** 粗算 messages token(字符数/4,英文 ~4 char/token;spec §6.2 粗估够)。 */
    private static int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage m : messages) {
            chars += m.content() == null ? 0 : m.content().length();
        }
        return chars / 4;
    }

    /**
     * 摘要历史:用同 key+adapter 发摘要请求(messages=[system 摘要指令, user 拼接历史],
     * max_tokens=500),阻塞取完整摘要文本。失败抛异常(由 {@link #compressHistoryIfNeeded} 兜底)。
     */
    private String summarize(
            LlmProviderAdapter adapter, String apiSecret, String baseUrl, String model, List<ChatMessage> toCompress) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : toCompress) {
            sb.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        List<ChatMessage> summaryReq = List.of(
                new ChatMessage("system", "你是策略对话摘要助手。摘要以下对话历史,保留关键决策、参数调整、代码改动、未解决问题,200字以内。"),
                new ChatMessage("user", sb.toString()));
        LlmStreamRequest req = new LlmStreamRequest(apiSecret, baseUrl, model, summaryReq, 0.3, SUMMARY_MAX_TOKENS);
        return adapter.stream(req)
                .timeout(SUMMARY_TIMEOUT)
                .reduce("", String::concat)
                .block();
    }
}

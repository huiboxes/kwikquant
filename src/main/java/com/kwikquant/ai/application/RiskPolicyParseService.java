package com.kwikquant.ai.application;

import com.kwikquant.account.application.LlmApiKeyService;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.ai.domain.AiUsageSource;
import com.kwikquant.ai.domain.LlmProviderNotSupportedException;
import com.kwikquant.ai.domain.RiskIntentParseException;
import com.kwikquant.risk.application.RiskPolicyParamValidator;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.shared.types.LlmProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 自然语言风控规则解析:用户自然语言描述 → 用户自己的 LLM key → 结构化规则预览(不落库)。
 *
 * <p><b>形态选型</b>:同步非流式"一问一答拿 JSON",复用 {@code AiChatService.summarize} 的
 * "流式 SPI 聚合同步全文"范式({@code adapter.stream(...).reduce().block()})——不给
 * {@link LlmProviderAdapter} SPI 加非流式方法。结构化输出走 <b>prompt 约束 + 防御性解析</b>,
 * 不用 {@code response_format}:三 provider 异构(Anthropic Messages API 无此参数,
 * OPENAI_COMPATIBLE 支持度参差),全仓 adapter 请求体也无此先例。
 *
 * <p><b>预览与落库分离</b>:本服务只产出预览(经 {@link RiskPolicyParamValidator} 与落库同口径校验),
 * 确认落库走 {@code POST /api/v1/risk/policies/apply}(risk 模块,事务原子),ai 模块不写风控数据。
 *
 * <p><b>范围边界</b>:system prompt 硬约束"只提取用户明确给出的阈值,不得推荐/补全"
 * (不做 AI 主动推荐阈值);频率窗口仅支持每分钟口径(与 {@code OrderFrequencyEvaluator} 一致)。
 */
@Service
public class RiskPolicyParseService {

    private static final Logger log = LoggerFactory.getLogger(RiskPolicyParseService.class);

    /** 解析请求 max_tokens(输出是紧凑 JSON,1024 足够)。 */
    private static final int PARSE_MAX_TOKENS = 1024;

    /** 解析调用超时(防 provider 吊死 servlet 线程;比摘要 30s 略宽,留小模型推理余量)。 */
    private static final Duration PARSE_TIMEOUT = Duration.ofSeconds(45);

    /** 单次解析最多保留规则数(与 ruleType 种类一致)。 */
    private static final int MAX_PARSED_RULES = RiskRuleType.values().length;

    /** summary 展示长度上限(防 LLM 借 summary 长篇输出)。 */
    private static final int MAX_SUMMARY_CHARS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
            """
            你是量化交易系统的风控规则解析助手。用户用自然语言描述风控要求,你将其提取为结构化规则 JSON。

            仅支持以下 4 种规则类型(ruleType 必须是其中之一):
            1. MAX_NOTIONAL — 单笔订单名义额上限(USDT 估值),params: {"maxNotionalUsdt": "<金额>"}
            2. DAILY_LOSS_LIMIT — 当日最大已实现亏损(USDT),params: {"maxLossUsdt": "<金额>"}
            3. ORDER_FREQUENCY — 每分钟最大下单次数,params: {"maxPerMinute": "<正整数>"}
            4. MAX_INITIAL_MARGIN — 合约初始保证金占用上限比例,params: {"maxInitialMarginRatio": "<0到1的小数>"}

            输出要求:
            - 只输出一个 JSON 对象,不要任何解释,不要 markdown 代码围栏。格式:
            {"summary":"<一句话复述用户意图>","rules":[{"ruleType":"<类型>","name":"<简短中文名>","params":{"<参数key>":"<参数值>"}}]}
            - params 值一律为字符串;金额为纯数字小数(不带千分位、单位、货币符号);"60%" 这类比例写 0.6。
            - 只提取用户明确给出的阈值;绝对不要推荐、推测或补全用户未说明的数值。缺阈值的规则直接不输出。
            - 每种 ruleType 至多一条规则;用户未提及的规则不要输出。
            - 频率只支持"每分钟"口径:若用户按其他窗口描述(如"每天最多 N 单"),换算为每分钟并向下取整(更保守),并在 summary 里说明换算。
            - 若描述里没有可识别的风控规则,输出 {"summary":"<简要说明原因>","rules":[]}。
            """;

    /** ruleType → 缺省名称(LLM 未给 name 时兜底,保证预览可读)。 */
    private static final Map<RiskRuleType, String> DEFAULT_NAMES = new EnumMap<>(RiskRuleType.class);

    static {
        DEFAULT_NAMES.put(RiskRuleType.MAX_NOTIONAL, "单笔名义额上限");
        DEFAULT_NAMES.put(RiskRuleType.DAILY_LOSS_LIMIT, "当日最大亏损");
        DEFAULT_NAMES.put(RiskRuleType.ORDER_FREQUENCY, "下单频率上限");
        DEFAULT_NAMES.put(RiskRuleType.MAX_INITIAL_MARGIN, "初始保证金占用上限");
    }

    private final LlmApiKeyService keyService;
    private final AiUsageLogService usageLogService;
    private final Map<LlmProvider, LlmProviderAdapter> adapters;

    public RiskPolicyParseService(
            LlmApiKeyService keyService, AiUsageLogService usageLogService, List<LlmProviderAdapter> adapterList) {
        this.keyService = keyService;
        this.usageLogService = usageLogService;
        this.adapters = new EnumMap<>(LlmProvider.class);
        for (LlmProviderAdapter a : adapterList) {
            this.adapters.put(a.provider(), a);
        }
    }

    /**
     * 解析自然语言风控描述为规则预览。
     *
     * @throws LlmProviderNotSupportedException adapter 未注入(服务端配置问题,8002)
     * @throws LlmProviderException             provider 调用失败(8003/502,由 AiExceptionHandler 兜底)
     * @throws RiskIntentParseException         LLM 输出无法提取出任何合法规则(8004/400)
     */
    public ParseResult parse(RiskPolicyParseRequest request, long userId) {
        LlmApiKey key = keyService.getOwned(request.llmKeyId(), userId);
        LlmProviderAdapter adapter = adapters.get(key.getProvider());
        if (adapter == null) {
            throw new LlmProviderNotSupportedException(key.getProvider());
        }
        String apiSecret = keyService.decryptSecret(key);
        // model 优先级与 AiChatService.chat 一致:request.model > key available_models 首项 > adapter 内置默认
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model()
                : keyService.defaultModelOf(key);
        List<ChatMessage> messages =
                List.of(new ChatMessage("system", SYSTEM_PROMPT), new ChatMessage("user", request.text()));
        LlmStreamRequest req =
                new LlmStreamRequest(apiSecret, key.getBaseUrl(), model, messages, 0.0, PARSE_MAX_TOKENS);
        MutableUsageSink sink = new MutableUsageSink();
        String raw;
        try {
            raw = adapter.stream(req, sink)
                    .timeout(PARSE_TIMEOUT)
                    .reduce("", String::concat)
                    .block();
        } catch (LlmProviderException e) {
            throw e; // provider 错误(网络/4xx/5xx)→ AiExceptionHandler 8003/502
        } catch (RuntimeException e) {
            // 超时(TimeoutException)/reactor 内部异常:非 provider 语义错误,归入解析失败
            log.warn("risk intent parse LLM call failed", e);
            throw new RiskIntentParseException(
                    "LLM call failed: " + e.getClass().getSimpleName());
        }
        try {
            usageLogService.log(
                    userId,
                    request.llmKeyId(),
                    model,
                    sink.promptTokens(),
                    sink.completionTokens(),
                    AiUsageSource.RISK_PARSE);
        } catch (Exception e) {
            log.warn("usage log dispatch failed", e);
        }
        return extractRules(raw);
    }

    /**
     * 从 LLM 原始输出提取规则:剥围栏 → 截取首尾大括号 → readTree → 逐条校验(ruleType 枚举 +
     * {@link RiskPolicyParamValidator} 与落库同口径)。非法/重复(ruleType 重复取先出现者)条目丢弃仅记日志;
     * 全部丢弃则抛 {@link RiskIntentParseException}。
     */
    ParseResult extractRules(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RiskIntentParseException("empty LLM output");
        }
        String json = extractJsonObject(raw);
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            log.warn("risk intent parse: LLM output is not valid JSON ({} chars)", raw.length());
            throw new RiskIntentParseException("LLM output not valid JSON");
        }
        String summary = root.path("summary").asText("");
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS);
        }
        JsonNode rulesNode = root.path("rules");
        List<ParsedRule> rules = new ArrayList<>();
        Set<RiskRuleType> seen = EnumSet.noneOf(RiskRuleType.class);
        if (rulesNode.isArray()) {
            for (JsonNode n : rulesNode) {
                ParsedRule rule = toRule(n);
                if (rule == null) {
                    continue;
                }
                // 同 ruleType 重复:取先出现者(与"同账户同 ruleType 唯一"的落库约束对齐)
                if (seen.add(rule.ruleType()) && rules.size() < MAX_PARSED_RULES) {
                    rules.add(rule);
                }
            }
        }
        if (rules.isEmpty()) {
            throw new RiskIntentParseException("no valid rules extracted from LLM output");
        }
        return new ParseResult(summary, List.copyOf(rules));
    }

    /** 解析单条规则;ruleType/params 任一非法返 null(调用方跳过)。 */
    private ParsedRule toRule(JsonNode n) {
        RiskRuleType ruleType;
        try {
            ruleType = RiskRuleType.valueOf(n.path("ruleType").asText("").trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.info(
                    "risk intent parse: skip rule with unknown ruleType '{}'",
                    n.path("ruleType").asText(""));
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        JsonNode paramsNode = n.path("params");
        if (paramsNode.isObject()) {
            for (Map.Entry<String, JsonNode> f : paramsNode.properties()) {
                params.put(f.getKey(), f.getValue().asText(""));
            }
        }
        try {
            RiskPolicyParamValidator.validate(ruleType, params);
        } catch (IllegalArgumentException e) {
            log.info("risk intent parse: skip invalid {} rule: {}", ruleType, e.getMessage());
            return null;
        }
        String name = n.path("name").asText("").trim();
        if (name.isEmpty()) {
            name = DEFAULT_NAMES.get(ruleType);
        }
        return new ParsedRule(ruleType, name, Map.copyOf(params));
    }

    /** 剥 markdown 围栏与前导杂文:取首个 '{' 到末个 '}'(容忍 LLM 输出前后带解释)。 */
    static String extractJsonObject(String raw) {
        String s = raw.strip().replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "");
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return s;
        }
        return s.substring(start, end + 1);
    }

    /** 解析结果:summary 一句话复述 + 已通过落库口径校验的规则列表。 */
    public record ParseResult(String summary, List<ParsedRule> rules) {}

    /** 单条解析规则(ruleType 枚举 + 缺省兜底后的 name + 参数)。 */
    public record ParsedRule(RiskRuleType ruleType, String name, Map<String, String> params) {}
}

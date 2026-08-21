package com.kwikquant.ai.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.LlmApiKeyService;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.ai.domain.AiUsageSource;
import com.kwikquant.ai.domain.LlmProviderNotSupportedException;
import com.kwikquant.ai.domain.RiskIntentParseException;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.shared.types.LlmProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Pure-Mockito unit tests for {@link RiskPolicyParseService}(自然语言风控解析)。
 * 风格与 {@link AiChatServiceTest} 一致:adapter 按接口 mock,流断言走 reduce().block() 同步路径。
 */
class RiskPolicyParseServiceTest {

    private LlmApiKeyService keyService;
    private AiUsageLogService usageLogService;
    private LlmProviderAdapter openaiAdapter;
    private RiskPolicyParseService service;

    @BeforeEach
    void setUp() {
        keyService = mock(LlmApiKeyService.class);
        usageLogService = mock(AiUsageLogService.class);
        openaiAdapter = mock(LlmProviderAdapter.class);
        when(openaiAdapter.provider()).thenReturn(LlmProvider.OPENAI);
        service = new RiskPolicyParseService(keyService, usageLogService, List.of(openaiAdapter));
    }

    private LlmApiKey key(long id, LlmProvider provider) {
        LlmApiKey k = new LlmApiKey();
        k.setId(id);
        k.setUserId(42L);
        k.setProvider(provider);
        return k;
    }

    private RiskPolicyParseRequest req(String text) {
        return new RiskPolicyParseRequest(1L, text, null);
    }

    /** stub adapter 流并记录 usage(模拟 provider 末帧 usage)。 */
    private void stubLlm(String output) {
        when(openaiAdapter.stream(any(), any())).thenAnswer(inv -> {
            inv.getArgument(1, UsageSink.class).accept(10, 20);
            return Flux.just(output);
        });
    }

    // ---------- happy path ----------

    @Test
    void parse_twoRules_mapsEnumNameParams_andLogsUsageAsRiskParse() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm(
                """
                {"summary":"单笔不超过 5000，每天最多亏 2000","rules":[
                  {"ruleType":"MAX_NOTIONAL","name":"单笔上限","params":{"maxNotionalUsdt":"5000"}},
                  {"ruleType":"DAILY_LOSS_LIMIT","name":"日亏损上限","params":{"maxLossUsdt":"2000"}}]}""");

        RiskPolicyParseService.ParseResult result = service.parse(req("单笔不超过5000，每天最多亏2000"), 42L);

        assertThat(result.summary()).contains("5000");
        assertThat(result.rules()).hasSize(2);
        assertThat(result.rules().get(0).ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
        assertThat(result.rules().get(0).name()).isEqualTo("单笔上限");
        assertThat(result.rules().get(0).params()).containsEntry("maxNotionalUsdt", "5000");
        assertThat(result.rules().get(1).ruleType()).isEqualTo(RiskRuleType.DAILY_LOSS_LIMIT);
        // usage 落库 source=RISK_PARSE(token 已实际消耗,无论解析成败都记账)
        verify(usageLogService).log(eq(42L), eq(1L), any(), eq(10), eq(20), eq(AiUsageSource.RISK_PARSE));
    }

    @Test
    void parse_sendsSystemPromptAndUserText_temperatureZero() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm(
                "{\"summary\":\"x\",\"rules\":[{\"ruleType\":\"ORDER_FREQUENCY\",\"params\":{\"maxPerMinute\":\"3\"}}]}");

        service.parse(req("每分钟最多下 3 单"), 42L);

        ArgumentCaptor<LlmStreamRequest> captor = ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        LlmStreamRequest passed = captor.getValue();
        assertThat(passed.messages()).hasSize(2);
        assertThat(passed.messages().get(0).role()).isEqualTo("system");
        assertThat(passed.messages().get(0).content()).contains("风控规则解析助手");
        assertThat(passed.messages().get(1).role()).isEqualTo("user");
        assertThat(passed.messages().get(1).content()).isEqualTo("每分钟最多下 3 单");
        // 结构化解析:temperature=0 求确定性
        assertThat(passed.temperature()).isEqualTo(0.0);
        assertThat(passed.maxTokens()).isEqualTo(1024);
    }

    // ---------- 防御性解析 ----------

    @Test
    void parse_jsonWrappedInCodeFences_stripped() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm(
                "```json\n{\"summary\":\"x\",\"rules\":[{\"ruleType\":\"ORDER_FREQUENCY\",\"params\":{\"maxPerMinute\":\"5\"}}]}\n```");

        RiskPolicyParseService.ParseResult result = service.parse(req("限频"), 42L);

        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().get(0).ruleType()).isEqualTo(RiskRuleType.ORDER_FREQUENCY);
    }

    @Test
    void parse_preambleTextAroundJson_extracted() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm(
                "好的，解析如下：{\"summary\":\"x\",\"rules\":[{\"ruleType\":\"ORDER_FREQUENCY\",\"params\":{\"maxPerMinute\":\"2\"}}]} 希望有帮助！");

        RiskPolicyParseService.ParseResult result = service.parse(req("限频"), 42L);

        assertThat(result.rules()).hasSize(1);
    }

    @Test
    void parse_unknownRuleType_skipped() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm(
                """
                {"summary":"x","rules":[
                  {"ruleType":"STOP_LOSS","name":"止损","params":{"x":"1"}},
                  {"ruleType":"MAX_NOTIONAL","name":"上限","params":{"maxNotionalUsdt":"100"}}]}""");

        RiskPolicyParseService.ParseResult result = service.parse(req("x"), 42L);

        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().get(0).ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
    }

    @Test
    void parse_invalidParams_skipped() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        // 第一条金额为负(落库口径非法)→ 丢弃;第二条合法保留
        stubLlm(
                """
                {"summary":"x","rules":[
                  {"ruleType":"MAX_NOTIONAL","name":"非法","params":{"maxNotionalUsdt":"-1"}},
                  {"ruleType":"DAILY_LOSS_LIMIT","name":"合法","params":{"maxLossUsdt":"500"}}]}""");

        RiskPolicyParseService.ParseResult result = service.parse(req("x"), 42L);

        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().get(0).ruleType()).isEqualTo(RiskRuleType.DAILY_LOSS_LIMIT);
    }

    @Test
    void parse_duplicateRuleType_firstWins() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm(
                """
                {"summary":"x","rules":[
                  {"ruleType":"MAX_NOTIONAL","name":"先出现","params":{"maxNotionalUsdt":"100"}},
                  {"ruleType":"MAX_NOTIONAL","name":"后出现","params":{"maxNotionalUsdt":"200"}}]}""");

        RiskPolicyParseService.ParseResult result = service.parse(req("x"), 42L);

        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().get(0).name()).isEqualTo("先出现");
    }

    @Test
    void parse_missingName_fallsBackToDefault() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm(
                "{\"summary\":\"x\",\"rules\":[{\"ruleType\":\"MAX_NOTIONAL\",\"params\":{\"maxNotionalUsdt\":\"100\"}}]}");

        RiskPolicyParseService.ParseResult result = service.parse(req("x"), 42L);

        assertThat(result.rules().get(0).name()).isEqualTo("单笔名义额上限");
    }

    @Test
    void parse_summaryOverLimit_truncatedTo200Chars() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        String longSummary = "长".repeat(500);
        stubLlm("{\"summary\":\"" + longSummary
                + "\",\"rules\":[{\"ruleType\":\"ORDER_FREQUENCY\",\"params\":{\"maxPerMinute\":\"1\"}}]}");

        RiskPolicyParseService.ParseResult result = service.parse(req("x"), 42L);

        assertThat(result.summary()).hasSize(200);
    }

    // ---------- 失败路径 → RiskIntentParseException(8004) ----------

    @Test
    void parse_allRulesInvalid_throws() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm("{\"summary\":\"x\",\"rules\":[{\"ruleType\":\"BOGUS\",\"params\":{}}]}");

        assertThatThrownBy(() -> service.parse(req("x"), 42L)).isInstanceOf(RiskIntentParseException.class);
        // token 已消耗,usage 仍记账
        verify(usageLogService).log(anyLong(), anyLong(), any(), anyInt(), anyInt(), eq(AiUsageSource.RISK_PARSE));
    }

    @Test
    void parse_emptyRulesArray_throws() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm("{\"summary\":\"描述里没有风控规则\",\"rules\":[]}");

        assertThatThrownBy(() -> service.parse(req("今天天气不错"), 42L)).isInstanceOf(RiskIntentParseException.class);
    }

    @Test
    void parse_emptyOutput_throws() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.empty());

        assertThatThrownBy(() -> service.parse(req("x"), 42L)).isInstanceOf(RiskIntentParseException.class);
    }

    @Test
    void parse_notJson_throws() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        stubLlm("我无法理解你的请求。");

        assertThatThrownBy(() -> service.parse(req("x"), 42L)).isInstanceOf(RiskIntentParseException.class);
    }

    @Test
    void parse_providerError_propagatesWithoutUsageLog() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        when(openaiAdapter.stream(any(), any())).thenReturn(Flux.error(new LlmProviderException(429, "quota")));

        assertThatThrownBy(() -> service.parse(req("x"), 42L)).isInstanceOf(LlmProviderException.class);
        // provider 错误:流未产出 token,不记 usage
        verifyNoInteractions(usageLogService);
    }

    @Test
    void parse_unknownProvider_throwsNotSupported() {
        // adapter 列表只有 OPENAI,key 是 ANTHROPIC → 8002 语义
        LlmApiKey k = key(1L, LlmProvider.ANTHROPIC);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);

        assertThatThrownBy(() -> service.parse(req("x"), 42L)).isInstanceOf(LlmProviderNotSupportedException.class);
    }

    @Test
    void parse_keyNotOwned_propagatesKeyServiceException() {
        when(keyService.getOwned(1L, 42L)).thenThrow(new RuntimeException("not found"));

        assertThatThrownBy(() -> service.parse(req("x"), 42L)).isInstanceOf(RuntimeException.class);
        // 构造器注册 EnumMap 会调 provider(),只断言未发起 LLM 流
        verify(openaiAdapter, never()).stream(any(), any());
    }

    // ---------- model 优先级(与 AiChatService.chat 一致) ----------

    @Test
    void parse_requestModelPresent_overridesKeyDefault() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        k.setAvailableModels("[\"gpt-4o\"]");
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        when(keyService.defaultModelOf(k)).thenReturn("gpt-4o");
        stubLlm(
                "{\"summary\":\"x\",\"rules\":[{\"ruleType\":\"ORDER_FREQUENCY\",\"params\":{\"maxPerMinute\":\"1\"}}]}");

        service.parse(new RiskPolicyParseRequest(1L, "限频", "gpt-5"), 42L);

        ArgumentCaptor<LlmStreamRequest> captor = ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        assertThat(captor.getValue().model()).isEqualTo("gpt-5");
    }

    @Test
    void parse_blankModel_fallsBackToKeyDefault() {
        LlmApiKey k = key(1L, LlmProvider.OPENAI);
        when(keyService.getOwned(1L, 42L)).thenReturn(k);
        when(keyService.decryptSecret(k)).thenReturn("sk");
        when(keyService.defaultModelOf(k)).thenReturn("gpt-4o-mini");
        stubLlm(
                "{\"summary\":\"x\",\"rules\":[{\"ruleType\":\"ORDER_FREQUENCY\",\"params\":{\"maxPerMinute\":\"1\"}}]}");

        service.parse(new RiskPolicyParseRequest(1L, "限频", "  "), 42L);

        ArgumentCaptor<LlmStreamRequest> captor = ArgumentCaptor.forClass(LlmStreamRequest.class);
        verify(openaiAdapter).stream(captor.capture(), any());
        assertThat(captor.getValue().model()).isEqualTo("gpt-4o-mini");
    }

    // ---------- extractJsonObject 静态工具 ----------

    @Test
    void extractJsonObject_variousWrappings() {
        assertThat(RiskPolicyParseService.extractJsonObject("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(RiskPolicyParseService.extractJsonObject("前缀 {\"a\":1} 后缀")).isEqualTo("{\"a\":1}");
        assertThat(RiskPolicyParseService.extractJsonObject("```json\n{\"a\":1}\n```"))
                .isEqualTo("{\"a\":1}");
        // 无大括号:原样返回(readTree 阶段再报错)
        assertThat(RiskPolicyParseService.extractJsonObject("没有 JSON")).isEqualTo("没有 JSON");
    }
}

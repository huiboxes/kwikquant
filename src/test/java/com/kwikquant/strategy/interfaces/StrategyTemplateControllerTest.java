package com.kwikquant.strategy.interfaces;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kwikquant.shared.infra.GlobalExceptionHandler;
import com.kwikquant.strategy.application.StrategyTemplateService;
import com.kwikquant.strategy.application.TemplateForkResult;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyTemplate;
import com.kwikquant.strategy.domain.TemplateNotFoundException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link StrategyTemplateController} MockMvc 测试（standalone，JWT 经预设 SecurityContext 模拟，
 * 参照 {@code StrategyControllerTest} 模式）。
 */
class StrategyTemplateControllerTest {

    private static final StrategyTemplate TEMPLATE = new StrategyTemplate(
            "ma-double-cross",
            "均线双金叉",
            "MA5/MA10/MA20 双重确认金叉做多",
            List.of("趋势跟踪"),
            "BTC/USDT",
            "BINANCE",
            "1h",
            "{}",
            90,
            "def on_bar(bar, ctx): pass");

    private MockMvc mockMvc;
    private StrategyTemplateService templateService;

    @BeforeEach
    void setUp() {
        templateService = mock(StrategyTemplateService.class);
        var controller = new StrategyTemplateController(templateService);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("42", null, List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new StrategyExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void list_returnsTemplateMetadataWithoutSource() throws Exception {
        when(templateService.list()).thenReturn(List.of(TEMPLATE));

        mockMvc.perform(get("/api/v1/strategies/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].key").value("ma-double-cross"))
                .andExpect(jsonPath("$.data[0].name").value("均线双金叉"))
                .andExpect(jsonPath("$.data[0].tags[0]").value("趋势跟踪"))
                .andExpect(jsonPath("$.data[0].backtestWindowDays").value(90))
                // 列表不带源码（payload 控制，源码走详情端点）
                .andExpect(jsonPath("$.data[0].sourceCode").doesNotExist());
    }

    @Test
    void get_returnsDetailWithSource() throws Exception {
        when(templateService.require("ma-double-cross")).thenReturn(TEMPLATE);

        mockMvc.perform(get("/api/v1/strategies/templates/ma-double-cross"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value("ma-double-cross"))
                .andExpect(jsonPath("$.data.sourceCode").value("def on_bar(bar, ctx): pass"))
                .andExpect(jsonPath("$.data.parameters").value("{}"));
    }

    @Test
    void get_unknownKey_returns7008() throws Exception {
        when(templateService.require("nope")).thenThrow(new TemplateNotFoundException("nope"));

        mockMvc.perform(get("/api/v1/strategies/templates/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(7008));
    }

    @Test
    void fork_happyPath_returnsStrategyAndFirstBacktestTaskId() throws Exception {
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setId(77L);
        strategy.setName("均线双金叉");
        when(templateService.fork(eq("ma-double-cross"), eq(42L)))
                .thenReturn(new TemplateForkResult(strategy, 501L, null));

        mockMvc.perform(post("/api/v1/strategies/templates/ma-double-cross/fork"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.strategy.id").value(77))
                .andExpect(jsonPath("$.data.firstBacktestTaskId").value(501))
                // standalone MockMvc 用默认 mapper(null 字段序列化;生产 non_null inclusion 直接省略字段)
                .andExpect(jsonPath("$.data.backtestSkipReason").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void fork_backtestSkipped_returnsNullTaskIdWithReason() throws Exception {
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setId(77L);
        strategy.setName("均线双金叉");
        when(templateService.fork(eq("ma-double-cross"), anyLong()))
                .thenReturn(new TemplateForkResult(strategy, null, "回测并发配额已满，请稍后在策略工作台手动提交首次回测"));

        mockMvc.perform(post("/api/v1/strategies/templates/ma-double-cross/fork"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategy.id").value(77))
                .andExpect(jsonPath("$.data.firstBacktestTaskId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.backtestSkipReason").value(org.hamcrest.Matchers.containsString("配额")));
    }

    @Test
    void fork_unknownKey_returns7008() throws Exception {
        when(templateService.fork(eq("nope"), anyLong())).thenThrow(new TemplateNotFoundException("nope"));

        mockMvc.perform(post("/api/v1/strategies/templates/nope/fork"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(7008));
    }
}

package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyTemplate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** fork 落库编排测试：create → createDraft → publish 顺序与参数（同事务原子性由 @Transactional 承担，此处验调用契约）。 */
class TemplateForkCreatorTest {

    private static final StrategyTemplate TEMPLATE = new StrategyTemplate(
            "ma-double-cross",
            "均线双金叉",
            "desc",
            List.of("趋势跟踪"),
            "BTC/USDT",
            "BINANCE",
            "1h",
            "{}",
            90,
            "def on_bar(bar, ctx): pass");

    private StrategyCrudService crudService;
    private StrategyCodeService codeService;
    private TemplateForkCreator creator;

    @BeforeEach
    void setUp() {
        crudService = mock(StrategyCrudService.class);
        codeService = mock(StrategyCodeService.class);
        creator = new TemplateForkCreator(crudService, codeService);
    }

    @Test
    void createForked_createsSpotStrategyWithTemplateDefaults() {
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setId(77L);
        when(crudService.create(
                        eq(42L),
                        eq("均线双金叉"),
                        eq("desc"),
                        eq("BTC/USDT"),
                        eq("BINANCE"),
                        eq("SPOT"),
                        isNull(),
                        isNull(),
                        eq("1h"),
                        eq("{}")))
                .thenReturn(strategy);
        StrategyCode draft = new StrategyCode();
        draft.setId(900L);
        when(codeService.createDraft(eq(77L), eq(42L), eq("def on_bar(bar, ctx): pass"), anyString()))
                .thenReturn(draft);

        StrategyDefinition result = creator.createForked(42L, TEMPLATE);

        assertThat(result).isSameAs(strategy);
        // changelog 标注模板来源
        verify(codeService)
                .createDraft(eq(77L), eq(42L), eq("def on_bar(bar, ctx): pass"), contains("ma-double-cross"));
    }

    @Test
    void createForked_publishesDraftImmediately() {
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setId(77L);
        when(crudService.create(
                        eq(42L),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("SPOT"),
                        isNull(),
                        isNull(),
                        anyString(),
                        anyString()))
                .thenReturn(strategy);
        StrategyCode draft = new StrategyCode();
        draft.setId(900L);
        when(codeService.createDraft(eq(77L), eq(42L), anyString(), anyString()))
                .thenReturn(draft);

        creator.createForked(42L, TEMPLATE);

        // 顺序:create → createDraft → publish(fork 产物出生即带已发布代码,可立即回测/就绪)
        InOrder ordered = inOrder(crudService, codeService);
        ordered.verify(crudService)
                .create(
                        eq(42L),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("SPOT"),
                        isNull(),
                        isNull(),
                        anyString(),
                        anyString());
        ordered.verify(codeService).createDraft(eq(77L), eq(42L), anyString(), anyString());
        ordered.verify(codeService).publish(77L, 42L, 900L);
    }
}

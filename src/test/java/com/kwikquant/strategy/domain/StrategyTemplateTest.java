package com.kwikquant.strategy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyTemplateTest {

    @Test
    void validTemplate_copiesTagsDefensively() {
        StrategyTemplate t = new StrategyTemplate(
                "k",
                "名称",
                "desc",
                List.of("趋势跟踪"),
                "BTC/USDT",
                "BINANCE",
                "1h",
                "{}",
                90,
                "def on_bar(bar, ctx): pass");
        assertThat(t.tags()).containsExactly("趋势跟踪");
        assertThat(t.backtestWindowDays()).isEqualTo(90);
        // tags 不可变拷贝
        assertThatThrownBy(() -> t.tags().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullTags_becomeEmptyList() {
        StrategyTemplate t = new StrategyTemplate("k", "名称", null, null, "BTC/USDT", "BINANCE", "1h", "{}", 1, "src");
        assertThat(t.tags()).isEmpty();
    }

    @Test
    void blankKey_rejected() {
        assertThatThrownBy(() ->
                        new StrategyTemplate(" ", "名称", null, List.of(), "BTC/USDT", "BINANCE", "1h", "{}", 1, "src"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void blankName_rejected() {
        assertThatThrownBy(() ->
                        new StrategyTemplate("k", "", null, List.of(), "BTC/USDT", "BINANCE", "1h", "{}", 1, "src"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void nonPositiveWindow_rejected() {
        assertThatThrownBy(() ->
                        new StrategyTemplate("k", "名称", null, List.of(), "BTC/USDT", "BINANCE", "1h", "{}", 0, "src"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backtestWindowDays");
    }
}

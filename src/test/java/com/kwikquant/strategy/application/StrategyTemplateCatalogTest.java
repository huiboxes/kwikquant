package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.strategy.domain.StrategyTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 官方模板目录守护测试：模板是产品资产，元数据合法性 / 源码协议 / 回测窗口 bar 数
 * 在此集中校验，坏模板不进 fork 链路。
 */
class StrategyTemplateCatalogTest {

    /** 与 application.yaml kwikquant.backtest.max-bars 默认值一致。 */
    private static final long MAX_BARS = 100_000L;

    private final StrategyTemplateCatalog catalog = new StrategyTemplateCatalog();

    @BeforeEach
    void setUp() {
        catalog.load();
    }

    @Test
    void loadsAtLeastTenTemplates_keysUnique() {
        List<StrategyTemplate> all = catalog.all();
        assertThat(all).hasSizeGreaterThanOrEqualTo(10);
        Set<String> keys = new HashSet<>();
        for (StrategyTemplate t : all) {
            assertThat(keys.add(t.key())).as("模板 key 唯一: %s", t.key()).isTrue();
        }
    }

    @Test
    void allTemplates_validSymbolExchangeIntervalAndWindow() {
        for (StrategyTemplate t : catalog.all()) {
            assertThat(t.name()).isNotBlank();
            assertThat(t.description()).isNotBlank();
            assertThat(t.tags()).as("%s tags", t.key()).isNotEmpty();
            assertThat(t.symbol()).as("%s symbol", t.key()).contains("/");
            Exchange exchange = Exchange.valueOf(t.exchange());
            assertThat(exchange).as("%s exchange", t.key()).isNotEqualTo(Exchange.PAPER);
            Interval interval = Interval.fromCcxt(t.intervalValue());
            // 推荐窗口 bar 数不超 max-bars(否则 fork 自动首回测必被 3001 拒)
            long bars = (long) t.backtestWindowDays() * 86_400_000L / interval.toMillis();
            assertThat(bars).as("%s window bars", t.key()).isLessThanOrEqualTo(MAX_BARS);
            assertThat(t.parameters()).as("%s parameters", t.key()).isNotBlank();
        }
    }

    @Test
    void allTemplates_spotOnly_andSourceDefinesOnBar() {
        for (StrategyTemplate t : catalog.all()) {
            String src = t.sourceCode();
            assertThat(src).as("%s source", t.key()).isNotBlank();
            assertThat(src).as("%s 必须定义 on_bar", t.key()).contains("def on_bar");
            // SPOT 模板不应出现合约专属下单字段(回测 BacktestContext.place_order 也不接受)
            assertThat(src)
                    .as("%s 不得含合约字段", t.key())
                    .doesNotContain("position_effect")
                    .doesNotContain("leverage");
        }
    }

    @Test
    void allSources_areUniquePerKey() {
        Set<String> sources = new HashSet<>();
        for (StrategyTemplate t : catalog.all()) {
            assertThat(sources.add(t.sourceCode())).as("模板源码互不相同: %s", t.key()).isTrue();
        }
    }

    /** python3 可用时逐模板 py_compile：官方模板语法必须合法（无 python3 的环境跳过）。 */
    @Test
    void allSources_compileWithPython3(@TempDir Path tmp) throws IOException, InterruptedException {
        ProcessBuilder probe = new ProcessBuilder("python3", "--version").redirectErrorStream(true);
        int probeExit;
        try {
            probeExit = probe.start().waitFor();
        } catch (IOException e) {
            probeExit = 1;
        }
        assumeTrue(probeExit == 0, "python3 不可用,跳过语法编译守护");

        for (StrategyTemplate t : catalog.all()) {
            Path file = tmp.resolve(t.key() + ".py");
            Files.writeString(file, t.sourceCode(), StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("python3", "-m", "py_compile", file.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor()).as("py_compile %s: %s", t.key(), output).isEqualTo(0);
        }
    }

    @Test
    void get_unknownKey_returnsNull() {
        assertThat(catalog.get("no-such-template")).isNull();
    }

    @Test
    void firstTemplate_isBeginnerFriendlyEntry() {
        // 列表首位是入门款(前端按目录顺序展示)
        assertThat(catalog.all().get(0).key()).isEqualTo("ma-double-cross");
    }
}

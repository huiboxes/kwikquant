package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PriceLevel;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 撮合差分对拍(Java 侧):全量 {@code tests/fixtures/matching/*.json} 过 {@link MatchingKernel}。
 *
 * <p>fixtures 是撮合语义单一真相源({@code docs/matching-spec.md} §8)的机读形式;pytest
 * {@code test_matching_fixtures.py} 用同一批 fixtures 跑 Python 回测引擎,CI 双门控防语义漂移。
 * fixture 期望值用十进制字符串,比较走 {@code isEqualByComparingTo}(忽略 scale)。
 */
class MatchingKernelFixturesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURES_DIR = Path.of("tests/fixtures/matching");

    @TestFactory
    Stream<DynamicTest> allFixtures_matchKernelAgrees() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(FIXTURES_DIR)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("fixtures 目录非空").isNotEmpty();
        return files.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> runFixture(p)));
    }

    private void runFixture(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(file));
        Order order = buildOrder(root.path("order"));
        MarketSnapshot snap = buildSnapshot(root.path("snapshot"));
        MatchConfig config = buildConfig(root.path("config"));

        Optional<Fill> fill = MatchingKernel.match(order, snap, config);

        JsonNode expected = root.path("expected");
        if (expected.isNull()) {
            assertThat(fill).as("%s 应无成交", root.path("description").asText()).isEmpty();
            return;
        }
        assertThat(fill).as("%s 应有成交", root.path("description").asText()).isPresent();
        Fill f = fill.get();
        assertThat(f.getPrice())
                .as("price")
                .isEqualByComparingTo(expected.path("price").asText());
        assertThat(f.getQty())
                .as("qty")
                .isEqualByComparingTo(expected.path("qty").asText());
        assertThat(f.getFee())
                .as("fee")
                .isEqualByComparingTo(expected.path("fee").asText());
        assertThat(f.getFeeCurrency())
                .as("feeCurrency")
                .isEqualTo(expected.path("feeCurrency").asText());
        assertThat(f.getLiquidity())
                .as("liquidity")
                .isEqualTo(expected.path("liquidity").asText());
        // filledAt = 快照 timestamp(确定性,参与对拍;externalFillId 随机不参与)
        assertThat(f.getFilledAt()).isEqualTo(Instant.parse(snap.timestamp().toString()));
    }

    private static Order buildOrder(JsonNode node) {
        Order o = new Order();
        o.setId(1L);
        o.setAccountId(1L);
        o.setSymbol(node.path("symbol").asText("BTC/USDT"));
        o.setSide(OrderSide.valueOf(node.path("side").asText()));
        o.setOrderType(OrderType.valueOf(node.path("orderType").asText()));
        o.setAmount(new BigDecimal(node.path("amount").asText()));
        JsonNode price = node.path("price");
        o.setPrice(price.isNull() ? null : new BigDecimal(price.asText()));
        o.setStatus(OrderStatus.valueOf(node.path("status").asText("SUBMITTED")));
        o.setFilledQty(new BigDecimal(node.path("filledQty").asText("0")));
        o.setTimeInForce(TimeInForce.GTC);
        return o;
    }

    private static MarketSnapshot buildSnapshot(JsonNode node) {
        return new MarketSnapshot(
                Instant.parse(node.path("timestamp").asText()),
                decimalOrNull(node.path("last")),
                decimalOrNull(node.path("bid")),
                decimalOrNull(node.path("ask")),
                decimalOrNull(node.path("open")),
                decimalOrNull(node.path("high")),
                decimalOrNull(node.path("low")),
                decimalOrNull(node.path("close")),
                BigDecimal.ONE,
                levels(node.path("bids")),
                levels(node.path("asks")));
    }

    private static List<PriceLevel> levels(JsonNode node) {
        List<PriceLevel> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode level : node) {
                out.add(new PriceLevel(
                        new BigDecimal(level.get(0).asText()),
                        new BigDecimal(level.get(1).asText())));
            }
        }
        return out;
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        return node.isNull() ? null : new BigDecimal(node.asText());
    }

    private static MatchConfig buildConfig(JsonNode node) {
        return new MatchConfig(
                MatchingFidelity.valueOf(node.path("fidelity").asText("FAST")),
                new BigDecimal(node.path("marketSlippageBps").asText("5")),
                node.path("partialFillEnabled").asBoolean(false),
                new BigDecimal(node.path("makerFeeRate").asText("0.001")),
                new BigDecimal(node.path("takerFeeRate").asText("0.002")));
    }
}

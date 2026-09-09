package com.kwikquant.shared.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PERP 数学内核差分对拍(Java 侧):全量 {@code tests/fixtures/perp/*.json} 过 {@link PerpMath}。
 *
 * <p>fixtures 是内核语义单一真相源({@code docs/perp-math-spec.md} §5)的机读形式;pytest
 * {@code test_perp_math_fixtures.py} 用同一批 fixtures 跑 Python {@code kwikquant_worker/perp_math.py},
 * CI 双门控防语义漂移。数值比较走 {@code isEqualByComparingTo}(忽略 scale 表示,scale 契约由
 * spec §2 输出类别声明);错误用例经 {@link #ERROR_MAP} 映射到异常类型 + 消息子串(spec §4)。
 */
class PerpMathFixturesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURES_DIR = Path.of("tests/fixtures/perp");

    /** spec §4 错误码 → (异常类型, 消息子串)。新增码必须同步 Python runner 的 ERROR_MAP。 */
    private record ErrorExpectation(Class<? extends RuntimeException> type, String messageContains) {}

    private static final Map<String, ErrorExpectation> ERROR_MAP = Map.of(
            "POSITIVE_REQUIRED",
            new ErrorExpectation(IllegalArgumentException.class, "must be positive"),
            "NON_NEGATIVE_REQUIRED",
            new ErrorExpectation(IllegalArgumentException.class, "must be non-negative"),
            "NON_TERMINATING_CONTRACTS",
            new ErrorExpectation(ArithmeticException.class, "Non-terminating decimal expansion"),
            "OVER_CLOSE",
            new ErrorExpectation(IllegalArgumentException.class, "over-position"),
            "INVALID_LEVERAGE",
            new ErrorExpectation(IllegalArgumentException.class, "leverage must be >= 1"),
            "INVALID_RATE",
            new ErrorExpectation(IllegalArgumentException.class, "maintMarginRate must be in (0, 1)"),
            "INVALID_SIDE",
            new ErrorExpectation(IllegalArgumentException.class, "positionSide must be LONG or SHORT"));

    @TestFactory
    Stream<DynamicTest> allFixtures_perpMathAgrees() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(FIXTURES_DIR)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(files).as("fixtures 目录非空").isNotEmpty();
        return files.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> runFixture(p)));
    }

    private void runFixture(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(file));
        String function = root.path("function").asText();
        JsonNode in = root.path("input");
        JsonNode expected = root.path("expected");
        String description = root.path("description").asText();

        if (expected.has("error")) {
            String code = expected.path("error").asText();
            ErrorExpectation e = ERROR_MAP.get(code);
            assertThat(e).as("%s: 错误码 %s 已注册(spec §4)", description, code).isNotNull();
            assertThatThrownBy(() -> invoke(function, in))
                    .as("%s", description)
                    .isInstanceOf(e.type())
                    .hasMessageContaining(e.messageContains());
            return;
        }

        Object actual = invoke(function, in);
        if (actual instanceof PerpMath.PositionDelta delta) {
            assertDecimal(delta.newQty(), expected.path("newQty"), "newQty");
            JsonNode avg = expected.path("newAvgEntryPrice");
            if (avg.isNull()) {
                assertThat(delta.newAvgEntryPrice()).as("newAvgEntryPrice").isNull();
            } else {
                assertDecimal(delta.newAvgEntryPrice(), avg, "newAvgEntryPrice");
            }
            assertDecimal(delta.realizedPnlDelta(), expected.path("realizedPnlDelta"), "realizedPnlDelta");
            assertDecimal(delta.marginDelta(), expected.path("marginDelta"), "marginDelta");
        } else if (actual instanceof Boolean breached) {
            assertThat(breached)
                    .as("%s", description)
                    .isEqualTo(expected.path("value").asBoolean());
        } else if (actual instanceof BigDecimal value) {
            assertDecimal(value, expected.path("value"), "value");
        } else {
            throw new AssertionError("unexpected kernel output type: " + actual);
        }
    }

    private static void assertDecimal(BigDecimal actual, JsonNode expected, String field) {
        assertThat(actual).as("%s", field).isNotNull();
        assertThat(actual).as("%s", field).isEqualByComparingTo(expected.asText());
    }

    /** 分派表:fixture function → 内核调用。runner 只做解析/分派/比较,不含业务逻辑(spec §5)。 */
    private static Object invoke(String function, JsonNode in) {
        switch (function) {
            case "toContracts":
                return PerpMath.toContracts(dec(in, "coinAmount"), dec(in, "contractSize"));
            case "toCoin":
                return PerpMath.toCoin(dec(in, "contracts"), dec(in, "contractSize"));
            case "signedDelta":
                return PerpMath.signedDelta(OrderSide.valueOf(in.path("side").asText()), dec(in, "qty"));
            case "initialMargin":
                return PerpMath.initialMargin(dec(in, "price"), dec(in, "qty"), intArg(in, "leverage"));
            case "maintenanceMarginRequired":
                return PerpMath.maintenanceMarginRequired(
                        dec(in, "markPrice"), dec(in, "qty"), dec(in, "maintMarginRate"));
            case "liquidationPriceIsolated":
                return PerpMath.liquidationPriceIsolated(
                        dec(in, "avgEntryPrice"),
                        dec(in, "qty"),
                        dec(in, "margin"),
                        dec(in, "maintMarginRate"),
                        str(in, "positionSide"));
            case "marginBreached":
                return PerpMath.marginBreached(dec(in, "marginBalance"), dec(in, "maintMarginRequired"));
            case "fundingAmount":
                return PerpMath.fundingAmount(
                        str(in, "positionSide"), dec(in, "fundingRate"), dec(in, "markPrice"), dec(in, "qty"));
            case "closedPnl":
                return PerpMath.closedPnl(
                        str(in, "positionSide"), dec(in, "avgEntryPrice"), dec(in, "exitPrice"), dec(in, "closeQty"));
            case "weightedAvgEntryPrice":
                return PerpMath.weightedAvgEntryPrice(
                        dec(in, "oldAvgEntryPrice"), dec(in, "oldQty"), dec(in, "fillPrice"), dec(in, "fillQty"));
            case "frozenMarginRelease":
                return PerpMath.frozenMarginRelease(
                        dec(in, "currentFrozenMargin"), dec(in, "currentQty"), dec(in, "closeQty"));
            case "applyPositionDelta":
                return PerpMath.applyPositionDelta(
                        str(in, "positionSide"),
                        boolArg(in, "open"),
                        dec(in, "currentQty"),
                        dec(in, "currentAvgEntryPrice"),
                        dec(in, "currentFrozenMargin"),
                        dec(in, "fillQty"),
                        dec(in, "fillPrice"),
                        intArg(in, "leverage"));
            default:
                // 未知 function 必须显式失败,防 typo fixture 静默跳过(spec §5)
                throw new AssertionError("unknown function in fixture: " + function);
        }
    }

    /** 金额参数:键缺失与显式 null 一律以 null 传入内核(spec §5),触发对应校验码。 */
    private static BigDecimal dec(JsonNode in, String key) {
        JsonNode node = in.get(key);
        return node == null || node.isNull() ? null : new BigDecimal(node.asText());
    }

    private static String str(JsonNode in, String key) {
        JsonNode node = in.get(key);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 非金额参数无 null 语义:键缺失/类型错必须显式失败(与 Python runner 的 KeyError 对齐,防静默默认值)。 */
    private static int intArg(JsonNode in, String key) {
        JsonNode node = in.get(key);
        if (node == null || !node.isNumber()) {
            throw new AssertionError("fixture input missing numeric key: " + key);
        }
        return node.asInt();
    }

    private static boolean boolArg(JsonNode in, String key) {
        JsonNode node = in.get(key);
        if (node == null || !node.isBoolean()) {
            throw new AssertionError("fixture input missing boolean key: " + key);
        }
        return node.asBoolean();
    }
}

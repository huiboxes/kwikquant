package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.BacktestFundingDataMissingException;
import com.kwikquant.strategy.domain.BacktestNoMarketDataException;
import com.kwikquant.strategy.domain.BacktestRunnerException;
import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Worker 回测结果解析(PythonSubprocessBacktestRunner 与 DockerBacktestRunner 共用)。
 *
 * <p>协议:worker 正常结束 stdout 打印 section8 JSON(trades/equity_curve/params/metrics);
 * exit 2 + stderr {@code NO_MARKET_DATA:} 前缀 = 区间无数据(7304);exit 3 + stderr
 * {@code FUNDING_DATA_MISSING:} 前缀 = PERP 资金费序列运行期缺期(7308);其余非 0 = 通用失败(7300)。
 */
public final class BacktestResultParser {

    private BacktestResultParser() {}

    /**
     * 将 {@link SubprocessResult} 翻译为 {@link BacktestResult} 或对应异常。
     *
     * @throws BacktestRunnerException 超时/非 0 退出/stdout 空/截断/JSON 解析失败(7300 语义)
     * @throws BacktestNoMarketDataException exit 2(区间无历史数据,7304 语义)
     * @throws BacktestFundingDataMissingException exit 3(PERP 资金费序列缺失,7308 语义)
     */
    public static BacktestResult parse(SubprocessResult result, ObjectMapper objectMapper) {
        if (result.timedOut()) {
            throw new BacktestRunnerException("backtest worker timeout");
        }
        if (result.exitCode() == 2) {
            throw new BacktestNoMarketDataException(
                    extractMarkedMessage(result.stderr(), "NO_MARKET_DATA:", "回测区间无历史数据"));
        }
        if (result.exitCode() == 3) {
            throw new BacktestFundingDataMissingException(
                    extractMarkedMessage(result.stderr(), "FUNDING_DATA_MISSING:", "PERP 回测资金费数据缺失"));
        }
        if (result.exitCode() != 0) {
            throw new BacktestRunnerException("backtest worker exit " + result.exitCode() + ": " + result.stderr());
        }
        if (result.stdoutTruncated()) {
            throw new BacktestRunnerException("backtest worker stdout 超过上限被截断(回测结果过大,缩小回测区间或参数)");
        }
        String section8 = result.stdout() == null ? "" : result.stdout().trim();
        if (section8.isEmpty()) {
            throw new BacktestRunnerException("backtest worker stdout empty (no backtest result JSON)");
        }
        return parseSection8(section8, objectMapper);
    }

    /** section8 JSON → summary(totalPnl = equity_curve 末−首;tradeCount = trades 数) + 原文。 */
    static BacktestResult parseSection8(String section8Json, ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(section8Json);
        } catch (Exception e) {
            // worker 输出畸形 JSON(崩溃截断/编码错乱)→ 归 runner 失败(7300),不透传 Jackson 异常类型
            throw new BacktestRunnerException("backtest worker result JSON malformed: " + e.getMessage());
        }
        JsonNode trades = root.path("trades");
        int tradeCount = trades.isArray() ? trades.size() : 0;
        BigDecimal totalPnl = extractTotalPnl(root);
        return new BacktestResult(totalPnl, tradeCount, section8Json);
    }

    /** 总盈亏绝对额 = equity_curve 末−首(含未实现盈亏;与 report.totalReturn 收益率口径区分)。 */
    private static BigDecimal extractTotalPnl(JsonNode root) {
        JsonNode eq = root.path("equity_curve");
        if (!eq.isArray() || eq.isEmpty()) return BigDecimal.ZERO;
        BigDecimal first = new BigDecimal(eq.get(0).path("equity").asText("0"));
        BigDecimal last = new BigDecimal(eq.get(eq.size() - 1).path("equity").asText("0"));
        return last.subtract(first);
    }

    /** 从 worker stderr 行级提取标记前缀之后内容作 errorMessage;无标记则用 stderr 全文(兜底)。 */
    static String extractMarkedMessage(String stderr, String marker, String fallback) {
        if (stderr == null || stderr.isBlank()) {
            return fallback;
        }
        return stderr.lines()
                .filter(s -> s.startsWith(marker))
                .findFirst()
                .map(s -> s.substring(marker.length()).trim())
                .orElse(stderr.trim());
    }
}

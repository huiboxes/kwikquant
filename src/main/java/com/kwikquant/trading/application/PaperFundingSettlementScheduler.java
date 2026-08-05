package com.kwikquant.trading.application;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PAPER 资金费率 8h 结算调度器(档位 C-2)。每 8h(OKX 0/8/16 UTC)遍历 PAPER PERP 持仓,
 * 按 OKX fundingRate 算资金费,扣/加 paper_balance.free + 落账 funding_settlements。
 *
 * <p>符号约定(OKX 语义):正费率多头付空头收,负费率反。{@code applyFundingSettlement} 的 fundingAmount
 * 已带符号(正=收加 free,负=付扣 free),直接 free += fundingAmount。
 * <ul>
 *   <li>LONG: fundingAmount = -fundingRate × notional(正费率→负=付扣,负费率→正=收加)</li>
 *   <li>SHORT: fundingAmount = +fundingRate × notional(正费率→正=收加,负费率→负=付扣)</li>
 * </ul>
 *
 * <p>fundingRate 从 {@link MarketDataService#fetchFundingRate}(实盘 CCXT)拉,PAPER 模拟用实盘费率(模拟实盘语义)。
 * 每 symbol 一次 API 调用,8h 一次可接受。异常只 warn 不阻断其他 position 结算。
 */
@Component
public class PaperFundingSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaperFundingSettlementScheduler.class);

    private final ExchangeAccountService accountService;
    private final PositionService positionService;
    private final MarketDataService marketDataService;
    private final BalanceService balanceService;
    private final FundingSettlementService fundingSettlementService;

    public PaperFundingSettlementScheduler(
            ExchangeAccountService accountService,
            PositionService positionService,
            MarketDataService marketDataService,
            BalanceService balanceService,
            FundingSettlementService fundingSettlementService) {
        this.accountService = accountService;
        this.positionService = positionService;
        this.marketDataService = marketDataService;
        this.balanceService = balanceService;
        this.fundingSettlementService = fundingSettlementService;
    }

    /**
     * 8h 结算入口。OKX 资金费率结算时刻 0/8/16 UTC(Spring cron 6 字段:秒 分 时 日 月 周)。
     */
    @Scheduled(cron = "0 0 0,8,16 * * *")
    public void settleAll() {
        Instant settleTime = Instant.now();
        List<ExchangeAccount> paperAccounts = accountService.findAll().stream()
                .filter(ExchangeAccount::isPaperTrading)
                .toList();
        log.info("[paper-funding] 8h settlement start: accounts={} settleTime={}", paperAccounts.size(), settleTime);
        for (ExchangeAccount account : paperAccounts) {
            try {
                settleAccount(account, settleTime);
            } catch (RuntimeException e) {
                log.warn("[paper-funding] account {} settlement failed: {}", account.getId(), e.getMessage());
            }
        }
        log.info("[paper-funding] 8h settlement done: settleTime={}", settleTime);
    }

    /**
     * 结算单账户所有 PERP 持仓(CROSS + ISOLATED,跨 symbol)。非 flat 仓才结算。
     */
    void settleAccount(ExchangeAccount account, Instant settleTime) {
        long accountId = account.getId();
        List<Position> positions = positionService.findByAccount(accountId).stream()
                .filter(p -> p.getMarginMode() != null) // PERP(ISOLATED/CROSS);SPOT marginMode=null 跳过
                .filter(p -> !p.isFlat())
                .toList();
        if (positions.isEmpty()) return;
        for (Position p : positions) {
            try {
                settlePosition(account, p, settleTime);
            } catch (RuntimeException e) {
                log.warn("[paper-funding] position {} settle failed: {}", p.getId(), e.getMessage());
            }
        }
    }

    /**
     * 结算单仓:拉 fundingRate → 算 fundingAmount → 扣/加余额 + 落账。
     */
    void settlePosition(ExchangeAccount account, Position p, Instant settleTime) {
        FundingRate fr = marketDataService.fetchFundingRate(account.getExchange(), MarketType.PERP, p.getSymbol());
        if (fr == null || fr.fundingRate() == null || fr.markPrice() == null) {
            log.warn("[paper-funding] no fundingRate for {} (skip): accountId={}", p.getSymbol(), account.getId());
            return;
        }
        BigDecimal qty = p.getQty();
        BigDecimal notional = fr.markPrice().multiply(qty);
        // 正费率多头付(扣 free)空头收(加 free);LONG sideSign=-1(正费率→负=付扣),SHORT sideSign=+1(正费率→正=收加)
        BigDecimal sideSign = "short".equalsIgnoreCase(p.getSide()) ? BigDecimal.ONE : BigDecimal.valueOf(-1);
        BigDecimal fundingAmount =
                fr.fundingRate().multiply(notional).multiply(sideSign).setScale(8, RoundingMode.HALF_UP);

        balanceService.applyFundingSettlement(account.getId(), true, "USDT", fundingAmount);
        fundingSettlementService.processFundingSettlement(
                account.getId(), p.getId(), p.getSymbol(), fr.fundingRate(), qty, fundingAmount, settleTime);
        log.info(
                "[paper-funding] settled: accountId={} positionId={} symbol={} rate={} amount={}",
                account.getId(),
                p.getId(),
                p.getSymbol(),
                fr.fundingRate(),
                fundingAmount);
    }
}

package com.kwikquant.trading.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 档位 C-2 PaperFundingSettlementScheduler 单测。
 *
 * <p>覆盖:LONG 正费率付(扣 free)/SHORT 正费率收(加 free)/LONG 负费率收/无 fundingRate skip/account 过滤 PERP+非 flat。
 * 注:余额扣减调 {@link BalanceService#applyFundingSettlement}(account.application API,不直依赖 account.infrastructure)。
 */
class PaperFundingSettlementSchedulerTest {

    private ExchangeAccountService accountService;
    private PositionService positionService;
    private MarketDataService marketDataService;
    private BalanceService balanceService;
    private FundingSettlementService fundingSettlementService;
    private PaperFundingSettlementScheduler scheduler;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        positionService = mock(PositionService.class);
        marketDataService = mock(MarketDataService.class);
        balanceService = mock(BalanceService.class);
        fundingSettlementService = mock(FundingSettlementService.class);
        scheduler = new PaperFundingSettlementScheduler(
                accountService, positionService, marketDataService, balanceService, fundingSettlementService);
    }

    @Test
    void settlePosition_longPositiveRate_paysFromFree() {
        // LONG qty=0.01, rate=0.0001, mark=60000 → notional=600, sideSign=-1(多头付)
        // fundingAmount=0.0001×600×(-1)=-0.06(付扣 free)
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01");
        when(marketDataService.fetchFundingRate(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(fundingRate("0.0001", "60000"));

        scheduler.settlePosition(account, pos, Instant.parse("2026-08-05T00:00:00Z"));

        verify(balanceService)
                .applyFundingSettlement(
                        eq(7L),
                        eq(true),
                        eq("USDT"),
                        argThat(bd -> bd != null && bd.compareTo(new BigDecimal("-0.06")) == 0));
        verify(fundingSettlementService)
                .processFundingSettlement(
                        eq(7L),
                        eq(128L),
                        eq("BTC/USDT"),
                        argThat(bd -> bd != null && bd.compareTo(new BigDecimal("0.0001")) == 0),
                        argThat(bd -> bd != null && bd.compareTo(new BigDecimal("0.01")) == 0),
                        argThat(bd -> bd != null && bd.compareTo(new BigDecimal("-0.06")) == 0),
                        eq(Instant.parse("2026-08-05T00:00:00Z")));
    }

    @Test
    void settlePosition_shortPositiveRate_receivesToFree() {
        // SHORT qty=0.01, rate=0.0001, mark=60000 → sideSign=+1(空头收)
        // fundingAmount=0.0001×600×(+1)=+0.06(收加 free)
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_SHORT, "0.01");
        when(marketDataService.fetchFundingRate(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(fundingRate("0.0001", "60000"));

        scheduler.settlePosition(account, pos, Instant.parse("2026-08-05T00:00:00Z"));

        verify(balanceService)
                .applyFundingSettlement(
                        eq(7L),
                        eq(true),
                        eq("USDT"),
                        argThat(bd -> bd != null && bd.compareTo(new BigDecimal("0.06")) == 0));
    }

    @Test
    void settlePosition_longNegativeRate_receivesToFree() {
        // 负费率多头收:LONG qty=0.01, rate=-0.0001, mark=60000 → sideSign=-1
        // fundingAmount=-0.0001×600×(-1)=+0.06(收加 free)
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01");
        when(marketDataService.fetchFundingRate(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(fundingRate("-0.0001", "60000"));

        scheduler.settlePosition(account, pos, Instant.parse("2026-08-05T00:00:00Z"));

        verify(balanceService)
                .applyFundingSettlement(
                        eq(7L),
                        eq(true),
                        eq("USDT"),
                        argThat(bd -> bd != null && bd.compareTo(new BigDecimal("0.06")) == 0));
    }

    @Test
    void settlePosition_nullFundingRate_skips() {
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position pos = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01");
        when(marketDataService.fetchFundingRate(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(fundingRate(null, "60000"));

        scheduler.settlePosition(account, pos, Instant.parse("2026-08-05T00:00:00Z"));

        verify(balanceService, never()).applyFundingSettlement(anyLong(), anyBoolean(), anyString(), any());
        verify(fundingSettlementService, never())
                .processFundingSettlement(anyLong(), any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void settleAccount_filtersPerpNonFlatAndSettlesOnlyEligible() {
        ExchangeAccount account = paperAccount(7L, Exchange.OKX);
        Position perpLong = perpPosition(128L, "BTC/USDT", Position.SIDE_LONG, "0.01");
        Position spotPos = spotPosition(129L, "ETH/USDT"); // SPOT marginMode=null,跳过
        Position perpFlat = perpPosition(130L, "ETH/USDT", Position.SIDE_LONG, "0"); // flat,跳过
        when(positionService.findByAccount(7L)).thenReturn(List.of(perpLong, spotPos, perpFlat));
        when(marketDataService.fetchFundingRate(eq(Exchange.OKX), eq(MarketType.PERP), anyString()))
                .thenReturn(fundingRate("0.0001", "60000"));

        scheduler.settleAccount(account, Instant.parse("2026-08-05T00:00:00Z"));

        verify(balanceService, times(1)).applyFundingSettlement(eq(7L), eq(true), eq("USDT"), any());
    }

    private ExchangeAccount paperAccount(long id, Exchange exchange) {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(id);
        a.setExchange(exchange);
        a.setPaperTrading(true);
        return a;
    }

    private Position perpPosition(long id, String symbol, String side, String qty) {
        Position p = new Position();
        p.setId(id);
        p.setAccountId(7L);
        p.setSymbol(symbol);
        p.setSide(side);
        p.setPositionSide("long".equals(side) ? "LONG" : "SHORT");
        p.setQty(new BigDecimal(qty));
        p.setAvgEntryPrice(new BigDecimal("60000"));
        p.setLeverage(10);
        p.setMarginMode(MarginMode.ISOLATED);
        p.setFrozenAmount(BigDecimal.ZERO);
        return p;
    }

    private Position spotPosition(long id, String symbol) {
        Position p = new Position();
        p.setId(id);
        p.setAccountId(7L);
        p.setSymbol(symbol);
        p.setSide(Position.SIDE_LONG);
        p.setQty(new BigDecimal("0.1"));
        p.setMarginMode(null); // SPOT
        return p;
    }

    private FundingRate fundingRate(String rate, String markPrice) {
        return new FundingRate(
                Exchange.OKX,
                MarketType.PERP,
                "BTC/USDT",
                rate != null ? new BigDecimal(rate) : null,
                markPrice != null ? new BigDecimal(markPrice) : null,
                null,
                null,
                null,
                Instant.now());
    }
}

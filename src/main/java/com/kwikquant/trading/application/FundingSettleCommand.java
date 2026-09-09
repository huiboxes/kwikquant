package com.kwikquant.trading.application;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.trading.domain.FundingRateKind;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * PAPER 资金费期次结算命令({@link PaperFundingSettlementScheduler} →
 * {@link FundingSettlementService#processFundingSettlement})。
 *
 * @param accountId       模拟盘账户 ID
 * @param positionId      持仓 ID(期次幂等键成分,结算时必须非 flat)
 * @param symbol          CCXT 规范交易对(BTC/USDT)
 * @param fundingRate     本期费率(已结算值或预估值,由 rateKind 标注)
 * @param qty             结算时持仓量(币口径)
 * @param fundingAmount   资金费金额(已带符号:正=收加余额,负=付扣余额,PerpMath.fundingAmount 算)
 * @param fundingTime     结算期次时刻(交易所资金费网格;幂等键成分,settle_time := funding_time)
 * @param rateKind        费率值类型(SETTLED / PREDICTED)
 * @param intervalSeconds 期次间隔秒(8h=28800 / 4h=14400);未知传 null
 * @param markPrice       结算所用标记价;未知传 null
 * @param currency        结算币种(symbol quote 段派生,BTC/USDT→USDT;余额扣减不再硬编码)
 * @param marginMode      仓位保证金模式:ISOLATED 资金费侵蚀/增厚仓位保证金(OKX 逐仓语义),
 *                        CROSS 入账户现金;null 按 CROSS 口径(兼容)
 * @param rateSource      费率行来源(EXCHANGE / PROXY_BINANCE):跨所代理行入账时写进 audit
 *                        metadata 留观测痕迹;未知传 null
 */
public record FundingSettleCommand(
        long accountId,
        Long positionId,
        String symbol,
        BigDecimal fundingRate,
        BigDecimal qty,
        BigDecimal fundingAmount,
        Instant fundingTime,
        FundingRateKind rateKind,
        Integer intervalSeconds,
        BigDecimal markPrice,
        String currency,
        MarginMode marginMode,
        String rateSource) {}

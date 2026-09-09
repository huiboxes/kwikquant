package com.kwikquant.account.infrastructure;

import com.kwikquant.account.application.BalancePort;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.InsufficientBalanceException;
import com.kwikquant.account.domain.PaperBalance;
import com.kwikquant.shared.infra.QuoteCurrencyProperties;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 模拟盘余额适配器:读写 paper_balances 表(PaperExchange 内部持久化状态)。
 *
 * <p>职责:fetch(查余额) + initBalance(创建账户初始化主 quote 币种,默认 10 万 USDT,可配) + freeze/unfreeze(挂单冻结/撤单解冻)
 * + applyFill(成交扣减/入账) + reset(重置回初始值)。
 *
 * <p>CAS 乐观锁 + 重试 3 次,模式同 {@code PositionService.applyFill}。所有写操作不带自己的事务
 * (无 @Transactional),加入调用方事务(ExecutionService.processExecutionReport REQUIRED 事务),
 * 保证余额扣减 + 持仓更新 + 订单状态推进 + Fill insert 原子。
 *
 * <p>成交扣减语义(fee 从 quote 扣,MatchingKernel.inferFeeCurrency 统一返回 quote):
 * <ul>
 *   <li>BUY: freeze 时冻结 quote=price*qty; 成交时 quote 解冻 price*qty + 扣 fee, base 入账 qty</li>
 *   <li>SELL: freeze 时冻结 base=qty; 成交时 base 解冻 qty, quote 入账 price*qty-fee</li>
 * </ul>
 */
@Component
public class PaperBalanceAdapter implements BalancePort {

    private static final Logger log = LoggerFactory.getLogger(PaperBalanceAdapter.class);
    private static final int MAX_CAS_RETRIES = 3;

    private final PaperBalanceMapper mapper;
    private final QuoteCurrencyProperties quoteCurrencyProperties;

    public PaperBalanceAdapter(PaperBalanceMapper mapper, QuoteCurrencyProperties quoteCurrencyProperties) {
        this.mapper = mapper;
        this.quoteCurrencyProperties = quoteCurrencyProperties;
    }

    @Override
    public BalanceSnapshot fetch(ExchangeAccount account) {
        Map<String, BalanceSnapshot.CurrencyBalance> currencies = new LinkedHashMap<>();
        for (PaperBalance b : mapper.findByAccount(account.getId())) {
            currencies.put(
                    b.getCurrency(), new BalanceSnapshot.CurrencyBalance(b.getFree(), b.getUsed(), b.getTotal()));
        }
        return new BalanceSnapshot(currencies);
    }

    /** 创建模拟盘账户时初始化主 quote 币种(从 QuoteCurrencyProperties.primaryQuoteCurrency() 传入,默认 USDT)。幂等(重复调用静默,reset 重插场景)。 */
    public void initBalance(long accountId, String currency) {
        PaperBalance b = new PaperBalance();
        b.setAccountId(accountId);
        b.setCurrency(currency);
        BigDecimal initial = quoteCurrencyProperties.getPaperInitialBalance();
        b.setFree(initial);
        b.setUsed(BigDecimal.ZERO);
        b.setTotal(initial);
        b.setVersion(0);
        try {
            mapper.insert(b);
        } catch (DuplicateKeyException ex) {
            // 账户已有该 currency 行(reset 后重插或重复调用),幂等忽略
        }
    }

    /**
     * 冻结余额(挂单预占)。free -= amount, used += amount, total 不变。
     * 余额不足抛 {@link InsufficientBalanceException}(TradingService.submit catch 转 REJECTED)。
     */
    public void freeze(long accountId, String currency, BigDecimal amount) {
        if (amount.signum() <= 0) return;
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            PaperBalance b = mapper.findByAccountAndCurrency(accountId, currency);
            if (b == null) {
                throw new InsufficientBalanceException(
                        "insufficient balance: currency=" + currency + " free=0 required=" + amount);
            }
            if (b.getFree().compareTo(amount) < 0) {
                throw new InsufficientBalanceException(
                        "insufficient balance: currency=" + currency + " free=" + b.getFree() + " required=" + amount);
            }
            b.setFree(b.getFree().subtract(amount));
            b.setUsed(b.getUsed().add(amount));
            int affected = mapper.casUpdate(b);
            if (affected == 1) {
                b.setVersion(b.getVersion() + 1);
                return;
            }
        }
        throw new ResourceStateConflictException(
                "paper_balance freeze CAS failed: account=" + accountId + " currency=" + currency);
    }

    /**
     * 解冻余额(撤单/submit 失败补偿)。最多释放当前真实 used，避免重复或超额补偿凭空增加 free；total 不变。
     * 无行或已无冻结额时幂等 noop。
     */
    public void unfreeze(long accountId, String currency, BigDecimal amount) {
        if (amount.signum() <= 0) return;
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            PaperBalance b = mapper.findByAccountAndCurrency(accountId, currency);
            if (b == null) return; // 无行可解冻,幂等
            BigDecimal releaseAmount;
            if (b.getUsed().signum() < 0) {
                // used 已被 ISOLATED 资金费侵蚀打负(混合桶:订单冻结+锁定保证金−侵蚀):
                // min(amount, used) 会把合法解冻钳成 ≤0 静默吞掉——该账户所有撤单/GTD 解冻
                // 全部失效(free 拿不回钱),强平释放同被 cap 少释放,两本账发散。
                // 放行全额并 WARN 留痕;双重解冻防护由"终态必结算 + CAS 赢方唯一"承担。
                releaseAmount = amount;
                log.warn(
                        "[paper-balance] unfreeze with negative used (eroded margin): account={} currency={}"
                                + " currentUsed={} releasing={} (full release, clamp skipped)",
                        accountId,
                        currency,
                        b.getUsed(),
                        amount);
            } else {
                releaseAmount = amount.min(b.getUsed());
                if (releaseAmount.signum() <= 0) return;
                if (releaseAmount.compareTo(amount) < 0) {
                    log.warn(
                            "[paper-balance] unfreeze capped at current used: account={} currency={}"
                                    + " currentUsed={} requested={} released={}",
                            accountId,
                            currency,
                            b.getUsed(),
                            amount,
                            releaseAmount);
                }
            }
            b.setUsed(b.getUsed().subtract(releaseAmount));
            b.setFree(b.getFree().add(releaseAmount));
            int affected = mapper.casUpdate(b);
            if (affected == 1) {
                b.setVersion(b.getVersion() + 1);
                return;
            }
        }
        throw new ResourceStateConflictException(
                "paper_balance unfreeze CAS failed: account=" + accountId + " currency=" + currency);
    }

    /**
     * 穿蚀仓强平的负释放额划转(ISOLATED 资金费侵蚀 frozen&lt;0 → applyPerpDelta 旁路全平,
     * marginDelta=−frozen&gt;0 → 释放额为负)。{@link #unfreeze} 对非正额静默返,会在 used 留幻影
     * 负锁定;本方法反向划转:free += negativeRelease(负,吸收缺口)、used += |negativeRelease|
     * (归零)、total 不变(资金费侵蚀结算时已动过 total,守恒)。非负额防御性 noop(正值走 unfreeze)。
     */
    public void applyDepletedMarginRelease(long accountId, String currency, BigDecimal negativeRelease) {
        if (negativeRelease.signum() >= 0) return;
        applyDelta(accountId, currency, negativeRelease, negativeRelease.negate(), BigDecimal.ZERO);
    }

    /**
     * 应用成交到余额(ExecutionService.processExecutionReport 调,同事务)。
     * 不校验余额(撮合已发生,必须记账);fee 从 quote 扣(MatchingKernel.inferFeeCurrency 统一 quote)。
     *
     * <p>{@code frozenQuoteAmount}（仅 BUY 单有意义）：挂单时真实冻结的 quote 金额（{@code
     * Order.frozenQuoteAmount}）。不能直接用 {@code price*qty}（本次成交价）当"解冻量"——MARKET 单
     * 冻结价（下单时的 ticker 估价）跟成交价（撮合时的 ask/bid）系统性不同，两者一样只是巧合（LIMIT
     * 单碰巧相等）。传 null 时退化为旧逻辑（用 price*qty 顶替，仅用于兼容没有该字段的历史订单）。
     *
     * <p>按 {@code (marketType, positionEffect, marginMode)} 分支:
     * <ul>
     *   <li>{@code marketType==SPOT 或 null}:沿用 SPOT BUY/SELL 余额逻辑(逐字保留),BUY 释放 frozenQuote +
     *       扣 actualCost+fee(quote total-=)+ base 入账;SELL base 解冻 + quote 入账 price*qty-fee</li>
     *   <li>{@code PERP OPEN_* + ISOLATED}:保证金<b>转锁定</b>——挂单冻结的估算额 E 中,内核实际
     *       保证金 A({@code marginDelta})留在 used(仓位保证金池),差额 E−A 释放回 free,fee 扣
     *       free/total。used 从此反映 ISOLATED 存量占用(风控 MaxInitialMarginEvaluator 的
     *       used=total−free 口径随之看见真实占用)</li>
     *   <li>{@code PERP OPEN_* + CROSS(或 marginMode null 兼容)}:账户担保不划转,释放估算额回
     *       free + 扣 fee(旧行为逐字保留)</li>
     *   <li>{@code PERP CLOSE_* + ISOLATED}:解锁仓位保证金(释放额 = −{@code marginDelta},内核
     *       frozenMarginRelease 实际值)used→free + 扣 fee</li>
     *   <li>{@code PERP CLOSE_* + CROSS}:只扣 fee(fee&lt;0 返佣反向入账)。方向性平仓 PnL 由
     *       {@link #applyPnlSettlement} 单独结算,applyFill 不重复计</li>
     * </ul>
     */
    public void applyFill(
            long accountId,
            OrderSide side,
            String symbol,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal fee,
            BigDecimal frozenQuoteAmount,
            MarketType marketType,
            PositionEffect positionEffect,
            MarginMode marginMode,
            BigDecimal marginDelta) {
        String[] parts = symbol.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid symbol (expect BASE/QUOTE): " + symbol);
        }
        String base = parts[0];
        String quote = parts[1];
        BigDecimal safeFee = fee == null ? BigDecimal.ZERO : fee;
        if (marketType == MarketType.PERP) {
            applyPerpFill(
                    accountId, quote, price, qty, safeFee, frozenQuoteAmount, positionEffect, marginMode, marginDelta);
            return;
        }
        // SPOT(含 null 兼容历史 FillCommand)沿用原 BUY/SELL 逻辑
        if (side == OrderSide.BUY) {
            BigDecimal actualCost = price.multiply(qty);
            // 解冻量用挂单时真实冻结的量，不是本次成交价算出来的量——两者对 MARKET 单会系统性不同
            // （冻结价是下单时的 ticker 估价，成交价是撮合时的 ask/bid），用成交价当解冻量会让
            // used 残留漂移（长期运行累积成明显负数）。差价走 free 调整，total 始终按实际成本算，
            // 不受冻结价估得准不准影响。
            BigDecimal releaseFromUsed = frozenQuoteAmount != null ? frozenQuoteAmount : actualCost;
            BigDecimal freeAdjustment = releaseFromUsed.subtract(actualCost).subtract(safeFee);
            // quote: free += (releaseFromUsed - actualCost - fee), used -= releaseFromUsed, total -= actualCost+fee
            applyDelta(
                    accountId,
                    quote,
                    freeAdjustment,
                    releaseFromUsed.negate(),
                    actualCost.add(safeFee).negate());
            // base: free += qty, total += qty
            applyDelta(accountId, base, qty, BigDecimal.ZERO, qty);
        } else {
            // base: used -= qty(解冻), total -= qty, free 不变
            applyDelta(accountId, base, BigDecimal.ZERO, qty.negate(), qty.negate());
            // quote: free += price*qty-fee, total += price*qty-fee
            BigDecimal quoteGain = price.multiply(qty).subtract(safeFee);
            applyDelta(accountId, quote, quoteGain, BigDecimal.ZERO, quoteGain);
        }
    }

    /**
     * PERP 成交余额处理(交易所语义,按 marginMode 分流)。不碰 base(PERP 无 base 持仓,
     * 持仓记录在 positions 表,与余额表无关)。方向性 PnL 由 {@link #applyPnlSettlement} 单独结算,
     * 本方法不重复计(否则 PnL 会双扣/双加)。
     *
     * <p><b>ISOLATED(逐仓,锁定式)</b>:
     * <ul>
     *   <li>OPEN:挂单冻结估算额 E(frozenQuoteAmount)中的实际保证金 A(marginDelta,内核
     *       initialMargin)<b>转锁定</b>留在 used,E−A 释放回 free,fee 扣 free/total。
     *       A &gt; E(MARKET 单成交价高于冻结估价)时 free 被多扣、短暂为负——撮合已发生必须
     *       记账,风控缓冲(MaxInitialMarginEvaluator 80%)使该场景罕见,不做拒绝</li>
     *   <li>CLOSE:内核实际释放额 −marginDelta(frozenMarginRelease)从 used 解锁回 free,fee 扣 free</li>
     * </ul>
     * used 语义 = 挂单冻结 + ISOLATED 锁定保证金,与 positions.frozen_amount 逐笔一致;
     * ISOLATED 风控(used=total−free 口径)由此看见存量保证金占用。
     *
     * <p><b>CROSS(全仓)/marginMode null(历史兼容)</b>:账户担保不划转——OPEN 释放估算额回
     * free,CLOSE 只扣 fee(旧行为逐字保留)。
     *
     * <p>fee&lt;0(返佣)时 negate 为正,返佣入账,各分支符号语义一致。平仓 fee 必须在此扣除——
     * fills.realized_pnl_delta 与 positions.realized_pnl 均按净值记账,现金漏扣会使
     * paper_balances 系统性虚高 Σ(平仓 fee)。
     *
     * <p>{@code positionEffect} 必须非 null(PERP 必传),null 抛
     * {@link IllegalArgumentException}——静默当 OPEN 会掩盖调用方 bug。
     */
    private void applyPerpFill(
            long accountId,
            String quote,
            BigDecimal price,
            BigDecimal qty,
            BigDecimal safeFee,
            BigDecimal frozenQuoteAmount,
            PositionEffect positionEffect,
            MarginMode marginMode,
            BigDecimal marginDelta) {
        if (positionEffect == null) {
            throw new IllegalArgumentException("PERP fill requires non-null positionEffect (OPEN_*/CLOSE_*); got null");
        }
        boolean isolated = marginMode == MarginMode.ISOLATED;
        if (positionEffect == PositionEffect.CLOSE_LONG || positionEffect == PositionEffect.CLOSE_SHORT) {
            // CLOSE:ISOLATED 解锁仓位保证金(used→free,额=内核实际释放 −marginDelta);CROSS 无锁定不解锁
            BigDecimal release = isolated && marginDelta != null ? marginDelta.negate() : BigDecimal.ZERO;
            if (release.signum() != 0 || safeFee.signum() != 0) {
                // quote: free += (release − fee), used −= release, total −= fee
                applyDelta(accountId, quote, release.subtract(safeFee), release.negate(), safeFee.negate());
            }
            return;
        }
        // OPEN_LONG / OPEN_SHORT
        // PERP 必传 frozenQuoteAmount(挂单时真实冻结的估算保证金);null 退化为 price*qty 顶替,仅兼容历史订单
        BigDecimal releaseFromUsed = frozenQuoteAmount != null ? frozenQuoteAmount : price.multiply(qty);
        if (isolated) {
            // 保证金转锁定:实际保证金 A 留 used,估算差额 E−A 释放回 free,fee 扣 free/total。
            // marginDelta null(历史 FillCommand)退化 A=E:估算额全额锁定
            BigDecimal actualMargin = marginDelta != null ? marginDelta : releaseFromUsed;
            applyDelta(
                    accountId,
                    quote,
                    releaseFromUsed.subtract(actualMargin).subtract(safeFee),
                    actualMargin.subtract(releaseFromUsed),
                    safeFee.negate());
            return;
        }
        // CROSS / marginMode null:保证金不流出(账户担保),释放冻结 + 扣 fee(旧行为)
        // quote: free += (releaseFromUsed - fee), used -= releaseFromUsed, total -= fee
        applyDelta(accountId, quote, releaseFromUsed.subtract(safeFee), releaseFromUsed.negate(), safeFee.negate());
    }

    /**
     * PERP CLOSE_* 平仓 PnL 结算。由 ExecutionService/PositionService 在 applyPerpDelta 算出
     * realizedPnlDelta 后调用(同事务 REQUIRED)。
     *
     * <p>语义:盈亏直接进 free + total(free += pnlDelta, used 不变=0, total += pnlDelta)。
     * {@code pnlDelta} 可负(亏则减),实现模拟实盘"亏损也照常记账"的真实语义。
     *
     * <p>注意:applyFill 对 CLOSE_* 只扣 fee 不结算 PnL,故方向性 PnL 全靠此方法结算,无双重计账风险。
     */
    public void applyPnlSettlement(long accountId, String currency, BigDecimal pnlDelta) {
        applyDelta(accountId, currency, pnlDelta, BigDecimal.ZERO, pnlDelta);
    }

    /**
     * PAPER 资金费结算(CROSS 口径:入账户现金)。{@code fundingAmount} 已带符号(正=收加 free,
     * 负=付扣 free),语义同 {@link #applyPnlSettlement}(free += fundingAmount, used 不变,
     * total += fundingAmount)。CROSS 全仓的仓位担保就是账户余额,资金费直接收付现金。
     *
     * <p>符号约定(OKX 语义,由 PaperFundingSettlementScheduler 算):
     * <ul>
     *   <li>正费率多头付:LONG fundingAmount = -fundingRate × notional(正费率→负=付扣)</li>
     *   <li>正费率空头收:SHORT fundingAmount = +fundingRate × notional(正费率→正=收加)</li>
     * </ul>
     */
    public void applyFundingSettlement(long accountId, String currency, BigDecimal fundingAmount) {
        applyDelta(accountId, currency, fundingAmount, BigDecimal.ZERO, fundingAmount);
    }

    /**
     * PAPER 资金费结算(ISOLATED 口径:侵蚀/增厚仓位保证金池)。OKX 逐仓语义——资金费直接
     * 收付<b>仓位保证金</b>而非账户可用余额:used += fundingAmount(锁定池随之增减),
     * total += fundingAmount(总资产变动),free 不动。
     *
     * <p>{@code fundingAmount} 负=付(侵蚀,可把 used/frozen 打负=穿仓),正=收(增厚)。
     * 仓位侧 positions.frozen_amount 同步与 liquidationPrice 重算由
     * {@code PositionService.applyFundingErosion} 完成(同事务成对调用,两本账一致)。
     */
    public void applyIsolatedFundingErosion(long accountId, String currency, BigDecimal fundingAmount) {
        applyDelta(accountId, currency, BigDecimal.ZERO, fundingAmount, fundingAmount);
    }

    /**
     * 强平扣减专用。PAPER 模拟实盘"负余额保护":强平时若 free 不足以承受损失,
     * clamp delta 让 free 归 0(exchange takes the loss),total 跟 free 走,used 不变(=0,PERP 无持仓冻结)。
     *
     * <p>语义:正常路径(足够余额)直接 applyDelta(dFree, 0, dTotal);不足路径 clamp dFree=-free、
     * dTotal=dFree(同步归 0)。reset 不抹亏损——强平后账户余额可能远低于初始额,
     * 反映真实交易风险,只能手动 reset 重新注资。
     *
     * <p>注意:dFree 可负(扣减)可正(罕见,理论不会发生);clamp 只在 free+dFree<0 时触发,
     * 触发时打 WARN 日志(含账户/币种/原 free/原 delta/钳后值),便于审计。
     *
     * <p><strong>并发安全</strong>:clamp 决策(读 free 判是否归 0)在 {@link #applyDelta} 的 CAS 循环
     * 之外先算一次,若 CAS 重试期间 free 被并发改写,clamp 决策会过时(TOCTOU)。强平由 PaperExecutor
     * onTicker 在持仓/账户维度串行调用(同 account+symbol 单 tick 串行),实战并发概率低;严格并发安全
     * 需把 clamp 下沉到 applyDelta 循环或加外部锁,暂未实现。
     */
    public void applyLiquidationDelta(long accountId, String currency, BigDecimal dFree, BigDecimal dTotal) {
        PaperBalance current = mapper.findByAccountAndCurrency(accountId, currency);
        if (current == null) {
            // 无行可扣:理论上强平前账户必有该币种行(开仓时已初始化),无行视为已清零,静默 noop
            log.warn(
                    "[paper-balance] liquidation no row: account={} currency={} deltaFree={} → noop (already zero)",
                    accountId,
                    currency,
                    dFree);
            return;
        }
        BigDecimal effectiveFree = dFree;
        BigDecimal effectiveTotal = dTotal;
        if (current.getFree().add(dFree).signum() < 0) {
            effectiveFree = current.getFree().negate();
            effectiveTotal = effectiveFree;
            log.warn(
                    "[paper-balance] liquidation negative-balance clamp: account={} currency={} free={} delta={}"
                            + " → clamped to free=0",
                    accountId,
                    currency,
                    current.getFree(),
                    dFree);
        }
        applyDelta(accountId, currency, effectiveFree, BigDecimal.ZERO, effectiveTotal);
    }

    /**
     * 对某 currency 行叠加 delta(free/used/total)。行不存在则 insert(0 起步)。
     * CAS 重试 3 次,超限抛 {@link ResourceStateConflictException}(→ 上游事务回滚)。
     */
    private void applyDelta(long accountId, String currency, BigDecimal dFree, BigDecimal dUsed, BigDecimal dTotal) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            PaperBalance b = mapper.findByAccountAndCurrency(accountId, currency);
            if (b == null) {
                PaperBalance fresh = new PaperBalance();
                fresh.setAccountId(accountId);
                fresh.setCurrency(currency);
                fresh.setFree(dFree);
                fresh.setUsed(dUsed);
                fresh.setTotal(dTotal);
                fresh.setVersion(0);
                try {
                    mapper.insert(fresh);
                    return;
                } catch (DuplicateKeyException ex) {
                    continue; // 并发首次 insert 撞唯一键,重试取已有行
                }
            }
            b.setFree(b.getFree().add(dFree));
            b.setUsed(b.getUsed().add(dUsed));
            b.setTotal(b.getTotal().add(dTotal));
            int affected = mapper.casUpdate(b);
            if (affected == 1) {
                b.setVersion(b.getVersion() + 1);
                return;
            }
        }
        throw new ResourceStateConflictException("paper_balance CAS failed after " + MAX_CAS_RETRIES
                + " retries: account=" + accountId + " currency=" + currency);
    }

    /** 重置:清空所有余额行,重插主 quote 初始额(从 QuoteCurrencyProperties.primaryQuoteCurrency() 传入,默认 10 万 USDT,可配)。 */
    public void reset(long accountId, String currency) {
        mapper.deleteByAccount(accountId);
        initBalance(accountId, currency);
    }
}

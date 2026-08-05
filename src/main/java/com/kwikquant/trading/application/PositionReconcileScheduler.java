package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter.PositionSnapshot;
import com.kwikquant.trading.infrastructure.PositionMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 实盘持仓对账调度器(档位 B,bills 5s 轮询的 60s 兜底)。
 *
 * <p>bills 轮询是主路径(延迟 < 5s),本调度器兜底:bills 漏拉(OKX 接口故障/限频)时,
 * 60s 周期拉 {@link CcxtOrderAdapter#fetchSnapshot} 对比本地,发现"本地有 open PERP 持仓但 OKX 已无"
 * → 调 {@link LiquidationService#processLiquidation} 补强平。
 *
 * <p><b>仅兜底强平</b>:漏单(本地无+OKX 有)/qty 不一致(部分强平/手动改仓)只 log warn + 留账,
 * 不自动修正(避免误判用户手动操作)。
 *
 * <p><b>幂等</b>:bills 5s 已先处理 → position 已 flat → processLiquidation findById 拉到 flat
 * position(posSide=null)→ 抛 IllegalStateException → catch 当幂等跳过(log info)。
 *
 * <p><b>markPrice fallback</b>:reconcile 时 OKX 已强平,实际触发 markPrice 拉不到(过去时刻),
 * 用 position.liquidationPrice(强平价,≈触发时刻 markPrice)作 fallback。与 bills 路径的
 * bill.markPx(若 OKX 返)有偏差,但兜底场景可接受。
 *
 * <p>注入 {@link CcxtOrderAdapter} 而非直接 OkxRestClient + OkxOrderTranslator——
 * parsePositionsRest 包私有跨包访问不了,fetchSnapshot 已封装好(public)。
 */
@Component
public class PositionReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(PositionReconcileScheduler.class);

    private final ExchangeAccountService accountService;
    private final CcxtOrderAdapter ccxtAdapter;
    private final PositionMapper positionMapper;
    private final LiquidationService liquidationService;

    public PositionReconcileScheduler(
            ExchangeAccountService accountService,
            CcxtOrderAdapter ccxtAdapter,
            PositionMapper positionMapper,
            LiquidationService liquidationService) {
        this.accountService = accountService;
        this.ccxtAdapter = ccxtAdapter;
        this.positionMapper = positionMapper;
        this.liquidationService = liquidationService;
    }

    /**
     * 60s 周期对账所有实盘 OKX 账户。initialDelay 60s 避开启动恢复期(startupSnapshot 已对账一次)。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void reconcile() {
        List<ExchangeAccount> accounts = accountService.findAll();
        for (ExchangeAccount account : accounts) {
            if (account.isPaperTrading()) {
                continue; // 仅实盘(PaperExecutor.checkLiquidation 自处理模拟盘)
            }
            if (account.getExchange() != Exchange.OKX) {
                continue; // 仅 OKX(Binance/Bitget PERP 留账)
            }
            try {
                reconcileAccount(account);
            } catch (RuntimeException e) {
                log.warn("[reconcile] failed account={}: {}", account.getId(), e.getMessage());
            }
        }
    }

    private void reconcileAccount(ExchangeAccount account) {
        List<Position> local = positionMapper.findByAccount(account.getId());
        // 过滤 open PERP(marginMode != null + qty > 0)
        List<Position> openPerps = local.stream()
                .filter(p -> p.getMarginMode() != null)
                .filter(p -> p.getQty() != null && p.getQty().signum() > 0)
                .toList();
        if (openPerps.isEmpty()) {
            return; // 无 open PERP 持仓,跳过(无需拉 OKX)
        }

        CcxtOrderAdapter.AccountSnapshot snap;
        try {
            snap = ccxtAdapter.fetchSnapshot(account);
        } catch (RuntimeException e) {
            log.warn("[reconcile] fetchSnapshot failed account={}: {}", account.getId(), e.getMessage());
            return;
        }
        // OKX open 持仓 by symbol+posSide(只记 qty>0 的)
        Map<String, PositionSnapshot> okxByKey = new HashMap<>();
        for (PositionSnapshot s : snap.positions()) {
            if (s.qty() == null || s.qty().signum() <= 0) {
                continue;
            }
            String key = snapshotKey(s.symbol(), s.positionSide());
            okxByKey.put(key, s);
        }

        for (Position p : openPerps) {
            String key = positionKey(p.getSymbol(), p.getPositionSide());
            PositionSnapshot okxPos = okxByKey.get(key);
            if (okxPos == null) {
                // 本地 open + OKX 无 → bills 漏拉的强平,补
                BigDecimal markPrice = p.getLiquidationPrice();
                if (markPrice == null) {
                    log.warn(
                            "[reconcile] no liquidationPrice for 补强平 account={} positionId={}",
                            account.getId(),
                            p.getId());
                    continue;
                }
                try {
                    liquidationService.processLiquidation(p.getId(), markPrice, null);
                    log.warn(
                            "[reconcile] 补强平 account={} positionId={} symbol={} markPrice={}",
                            account.getId(),
                            p.getId(),
                            p.getSymbol(),
                            markPrice);
                } catch (IllegalStateException e) {
                    // position 已 flat(bills 5s 先处理,posSide=null)→ 幂等跳过
                    log.info("[reconcile] 补强平幂等跳过(position 已 flat): positionId={} {}", p.getId(), e.getMessage());
                }
            }
            // qty 不一致(部分强平/手动改仓)留账 warn,不自动修正
        }
    }

    /** 本地 Position key:symbol + positionSide(LONG/SHORT/net)。 */
    private static String positionKey(String symbol, String positionSide) {
        return symbol + ":" + (positionSide != null ? positionSide : "net");
    }

    /** OKX PositionSnapshot key:symbol + PositionSide.name()(LONG/SHORT/net)。 */
    private static String snapshotKey(String symbol, com.kwikquant.trading.domain.PositionSide positionSide) {
        return symbol + ":" + (positionSide != null ? positionSide.name() : "net");
    }
}

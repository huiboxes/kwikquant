package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter.PositionSnapshot;
import com.kwikquant.trading.infrastructure.PositionMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 实盘持仓对账调度器(bills 5s 轮询的 60s 兜底)。
 *
 * <p>bills 轮询是主路径(延迟 < 5s),本调度器兜底:bills 漏拉(OKX 接口故障/限频)时,
 * 60s 周期拉 {@link CcxtOrderAdapter#fetchSnapshot} 对比本地,发现"本地有 open PERP 持仓但 OKX 已无"
 * → 调 {@link LiquidationService#processLiquidation} 补强平。
 *
 * <p><b>仅兜底强平</b>:qty 不一致(部分强平/手动改仓)只 log warn 记录,不自动修正(避免误判
 * 用户手动操作)。<b>已知缺口</b>:反向扫描(本地无+OKX 有的脱管持仓)未实现,孤儿 OKX 行
 * 当前静默——启动快照与 fills 通道之外的脱管仓依赖人工发现( TD,随"实盘对账正确性"专项处理)。
 *
 * <p><b>幂等</b>:bills 5s 已先处理 → position 已 flat → processLiquidation 的显式 isFlat 守卫
 * position → 抛 IllegalStateException → catch 当幂等跳过(log info)。
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
    /** 补强平计数:本地 open PERP + OKX 已无 → bills 5s 主路径漏拉的强平被兜底。>0 = 主路径降级,需告警。 */
    private final Counter liquidationCaughtCounter;
    /** fetchSnapshot 失败计数:OKX REST 不可达/限频/鉴权错。持续增长 = 交易所连通性降级,需告警。 */
    private final Counter fetchFailCounter;
    /** qty 不一致计数:本地与 OKX 都有该持仓但 qty 不同(missed fill/部分强平/手动改仓)。需人工核查。 */
    private final Counter qtyMismatchCounter;

    /** 交易所侧消失但无单仓强平价的桶(CROSS 账户级判定无 liqPrice,或脏行)——不可虚构价清算,
     * ERROR + 本计数,人工处置信号。 */
    private final Counter crossGhostCounter;

    public PositionReconcileScheduler(
            ExchangeAccountService accountService,
            CcxtOrderAdapter ccxtAdapter,
            PositionMapper positionMapper,
            LiquidationService liquidationService,
            MeterRegistry meterRegistry) {
        this.accountService = accountService;
        this.ccxtAdapter = ccxtAdapter;
        this.positionMapper = positionMapper;
        this.liquidationService = liquidationService;
        this.liquidationCaughtCounter = Counter.builder("trading.reconcile.liquidation.caught")
                .description("补强平次数(本地 open PERP + OKX 已无,bills 主路径漏拉的兜底)")
                .register(meterRegistry);
        this.fetchFailCounter = Counter.builder("trading.reconcile.fetch.fail")
                .description("fetchSnapshot 失败次数(OKX REST 不可达/限频/鉴权)")
                .register(meterRegistry);
        this.qtyMismatchCounter = Counter.builder("trading.reconcile.qty.mismatch")
                .description("本地与 OKX 持仓 qty 不一致次数(missed fill/部分强平/手动改仓,需人工核查)")
                .register(meterRegistry);
        this.crossGhostCounter = Counter.builder("trading.reconcile.cross.ghost")
                .description("漏强平桶无强平价不可安全补(CROSS 账户级判定或脏行),人工处置")
                .register(meterRegistry);
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
                continue; // 仅 OKX(Binance/Bitget PERP 暂不支持)
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
            fetchFailCounter.increment();
            log.warn("[reconcile] fetchSnapshot failed account={}: {}", account.getId(), e.getMessage());
            return;
        }
        // 对账键 = symbol + posSide + marginMode(与本地桶身份对齐,OKX 也按 mgnMode 返回独立行)。
        // 旧键缺 marginMode:同 symbol 同 posSide 的 ISOLATED 与 CROSS 行互相覆盖(HashMap.put),
        // ISOLATED 桶被交易所强平时按覆盖后的键查到 CROSS 行 → 走 qty mismatch 而非补强平(漏检),
        // 反向覆盖时本地 CROSS 桶查空 → 按虚构 liquidationPrice 误清真实健在仓位(误补)。
        // 同键多行(本地 leverage 分桶 / OKX 防御性重复)聚合 qty 后比较,消灭逐 leverage 桶假告警刷屏。
        Map<String, List<PositionSnapshot>> okxByKey = new HashMap<>();
        // mgnMode 缺失行(协议变更/解析回归/非 PERP 行混入)不能按 "...:null" 键索引——本地桶键
        // 永远是 ISOLATED/CROSS,精确键 miss 会把健在仓位判成"OKX 无"→ 误补强平(真钱)。
        // 归入 symbol:posSide 通配桶:精确键 miss 时回退匹配,宁可 qty 告警不可虚构强平。
        Map<String, List<PositionSnapshot>> okxBySideWildcard = new HashMap<>();
        for (PositionSnapshot s : snap.positions()) {
            if (s.qty() == null || s.qty().signum() <= 0) {
                continue;
            }
            if (s.marginMode() == null) {
                log.warn(
                        "[reconcile] OKX snapshot row without marginMode (protocol anomaly, matched by"
                                + " side-wildcard): account={} symbol={} posSide={}",
                        account.getId(),
                        s.symbol(),
                        s.positionSide());
                okxBySideWildcard
                        .computeIfAbsent(snapshotKey(s.symbol(), s.positionSide(), null), k -> new ArrayList<>())
                        .add(s);
                continue;
            }
            okxByKey.computeIfAbsent(snapshotKey(s.symbol(), s.positionSide(), s.marginMode()), k -> new ArrayList<>())
                    .add(s);
        }
        Map<String, List<Position>> localByKey = new java.util.LinkedHashMap<>();
        for (Position p : openPerps) {
            localByKey
                    .computeIfAbsent(
                            positionKey(p.getSymbol(), p.getPositionSide(), p.getMarginMode()), k -> new ArrayList<>())
                    .add(p);
        }

        for (Map.Entry<String, List<Position>> entry : localByKey.entrySet()) {
            List<PositionSnapshot> okxRows = okxByKey.get(entry.getKey());
            if (okxRows == null) {
                // 精确桶键 miss → 通配回退(mgnMode 缺失行),避免把健在仓位误判为已被交易所强平。
                // 已知近似:ISOLATED 与 CROSS 两桶可对同一通配行各自回退,warn/counter 双份
                // (仅观测放大;比较不触发处置动作,无双重补强平风险)
                Position any = entry.getValue().get(0);
                okxRows = okxBySideWildcard.get(snapshotKey(any.getSymbol(), sideOf(any.getPositionSide()), null));
            }
            List<Position> localRows = entry.getValue();
            if (okxRows == null || okxRows.isEmpty()) {
                // 本地 open + OKX 无 → bills 漏拉的强平,逐桶行补
                for (Position p : localRows) {
                    catchUpLiquidation(account, p);
                }
            } else {
                // qty 不一致(missed fill/部分强平/手动改仓):按桶键聚合比较(Σlocal vs Σokx),
                // 只检测 + 告警不自动修正(避免误判用户在 OKX 手动改仓);compareTo 忽略 scale
                BigDecimal localQty = localRows.stream()
                        .map(Position::getQty)
                        .filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal okxQty = okxRows.stream()
                        .map(PositionSnapshot::qty)
                        .filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (localQty.compareTo(okxQty) != 0) {
                    qtyMismatchCounter.increment();
                    log.warn(
                            "[reconcile] qty mismatch account={} key={} localQty={} okxQty={} positionIds={}"
                                    + " (missed fill/部分强平/手动改仓,需人工核查)",
                            account.getId(),
                            entry.getKey(),
                            localQty,
                            okxQty,
                            localRows.stream().map(Position::getId).toList());
                }
            }
        }
    }

    /** 单桶行补强平:ISOLATED 用存量 liquidationPrice 作触发价近似;CROSS 无单仓强平价(账户级
     * 判定,liquidationPrice 恒 null)——不虚构价格强平(错价清算直接错钱),升 ERROR 留人工处置。 */
    private void catchUpLiquidation(ExchangeAccount account, Position p) {
        BigDecimal markPrice = p.getLiquidationPrice();
        if (markPrice == null) {
            crossGhostCounter.increment();
            log.error(
                    "[reconcile] missed liquidation but no liquidationPrice (CROSS bucket or dirty row),"
                            + " cannot catch up at a fabricated price: account={} positionId={} symbol={}"
                            + " marginMode={} (action=manual-review)",
                    account.getId(),
                    p.getId(),
                    p.getSymbol(),
                    p.getMarginMode());
            return;
        }
        try {
            liquidationService.processLiquidation(p.getId(), markPrice, null);
            liquidationCaughtCounter.increment();
            log.warn(
                    "[reconcile] 补强平 account={} positionId={} symbol={} markPrice={}",
                    account.getId(),
                    p.getId(),
                    p.getSymbol(),
                    markPrice);
        } catch (IllegalStateException e) {
            // position 已 flat(bills 5s 先处理,processLiquidation isFlat 守卫)→ 幂等跳过
            log.info("[reconcile] 补强平幂等跳过(position 已 flat): positionId={} {}", p.getId(), e.getMessage());
        }
    }

    /** 本地 positionSide 字符串 → PositionSide 枚举(通配键构造用;非法值返 null=net 段)。 */
    private static com.kwikquant.trading.domain.PositionSide sideOf(String positionSide) {
        if ("LONG".equals(positionSide)) {
            return com.kwikquant.trading.domain.PositionSide.LONG;
        }
        if ("SHORT".equals(positionSide)) {
            return com.kwikquant.trading.domain.PositionSide.SHORT;
        }
        return null;
    }

    /** 本地 Position 对账键:symbol + positionSide(LONG/SHORT/net) + marginMode(桶身份维度)。 */
    private static String positionKey(String symbol, String positionSide, MarginMode marginMode) {
        return symbol + ":" + (positionSide != null ? positionSide : "net") + ":" + marginMode;
    }

    /** OKX PositionSnapshot 对账键:symbol + PositionSide.name()(LONG/SHORT/net) + marginMode。 */
    private static String snapshotKey(
            String symbol, com.kwikquant.trading.domain.PositionSide positionSide, MarginMode marginMode) {
        return symbol + ":" + (positionSide != null ? positionSide.name() : "net") + ":" + marginMode;
    }
}

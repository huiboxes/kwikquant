package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.interfaces.OrderEvent;
import com.kwikquant.trading.interfaces.OrderWebSocketBroadcaster;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * GTD 订单到期扫描器。每 60s 扫描一次 orders 表，找出 expire_at 已过期且仍活跃的 GTD 订单，推进到 EXPIRED。
 *
 * <p>使用 DB 扫描而非内存池：Paper 模式有内存池可优化，但 Live 模式下交易所端可能也有 GTD 处理，统一从 DB 扫描更保守。
 */
@Component
public class GtdExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(GtdExpirationScheduler.class);

    private final OrderMapper orderMapper;
    private final OrderWebSocketBroadcaster wsBroadcaster;
    private final ExchangeAccountService accountService;
    private final TradingService tradingService;

    @Autowired
    public GtdExpirationScheduler(
            OrderMapper orderMapper,
            OrderWebSocketBroadcaster wsBroadcaster,
            ExchangeAccountService accountService,
            TradingService tradingService) {
        this.orderMapper = orderMapper;
        this.wsBroadcaster = wsBroadcaster;
        this.accountService = accountService;
        this.tradingService = tradingService;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    @Transactional
    public void scan() {
        Instant now = Instant.now();
        List<Order> expired = orderMapper.findExpiredGtd(now);
        if (expired.isEmpty()) return;
        log.info("[gtd-scheduler] found {} expired GTD orders", expired.size());
        for (Order o : expired) {
            try {
                String prevStatus = o.getStatus() != null ? o.getStatus().name() : null;
                o.transitionTo(OrderStatus.EXPIRED);
                int affected = orderMapper.casUpdate(o);
                if (affected == 1) {
                    o.setVersion(o.getVersion() + 1);
                    log.info("[gtd-scheduler] expired order: id={}", o.getId());

                    // 释放模拟盘冻结额（GTD 过期从未走 cancel/fill，此前一直漏做，导致 used 永久卡死）。
                    ExchangeAccount account = accountService.findById(o.getAccountId());
                    if (account != null && account.isPaperTrading()) {
                        try {
                            tradingService.unfreezeBalance(o, account);
                        } catch (RuntimeException e) {
                            log.warn(
                                    "[gtd-scheduler] unfreeze failed for expired order: id={} error={}",
                                    o.getId(),
                                    e.getMessage(),
                                    e);
                        }
                    }

                    // 事务提交后推送 WS 事件
                    long userId = account != null ? account.getUserId() : 0L;
                    final long orderId = o.getId();
                    final long accountId = o.getAccountId();
                    final long version = o.getVersion();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            wsBroadcaster.broadcast(
                                    userId,
                                    OrderEvent.statusChanged(
                                            orderId, accountId, prevStatus, OrderStatus.EXPIRED.name(), version));
                        }
                    });
                }
                // affected==0 → 状态已被其他线程推进，跳过
            } catch (RuntimeException e) {
                log.warn(
                        "[gtd-scheduler] skipping order id={} status={}: {}", o.getId(), o.getStatus(), e.getMessage());
            }
        }
    }
}

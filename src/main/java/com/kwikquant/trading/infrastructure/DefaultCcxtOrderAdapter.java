package com.kwikquant.trading.infrastructure;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.trading.domain.Order;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default CcxtOrderAdapter 实现。<strong>本 Wave 占位 — spike S1/S2 验证 CCXT Java 私有 API 可用性前，所有方法均抛
 * UnsupportedOperationException</strong>。
 *
 * <p>测试场景通过 mock CcxtOrderAdapter bean 覆盖。一旦 spike 完成，此类被替换为真实 CCXT 集成。
 *
 * <p>对应 Wave 4 reminder 中记录的待办：CCXT Java setSandboxMode + watchOrders 私有 WS 验证。
 */
@Component
@ConditionalOnMissingBean(name = "ccxtOrderAdapter")
public class DefaultCcxtOrderAdapter implements CcxtOrderAdapter {

    private static final Logger log = LoggerFactory.getLogger(DefaultCcxtOrderAdapter.class);

    @Override
    public String createOrder(ExchangeAccount account, Order order) {
        log.warn(
                "[ccxt-adapter] createOrder NOT IMPLEMENTED (spike S1/S2 pending): accountId={} orderId={} symbol={}",
                account.getId(),
                order.getId(),
                order.getSymbol());
        throw new ExchangeException(
                "CcxtOrderAdapter not implemented; waiting for spike S1/S2 validation. See docs/wave4-reminder.md",
                /*retryable=*/ false);
    }

    @Override
    public void cancelOrder(ExchangeAccount account, Order order) {
        log.warn("[ccxt-adapter] cancelOrder NOT IMPLEMENTED: accountId={} orderId={}", account.getId(), order.getId());
        throw new ExchangeException("CcxtOrderAdapter not implemented", false);
    }

    @Override
    public AccountSnapshot fetchSnapshot(ExchangeAccount account) {
        log.warn("[ccxt-adapter] fetchSnapshot NOT IMPLEMENTED: accountId={}", account.getId());
        return new AccountSnapshot(List.of(), List.of());
    }

    @Override
    public Runnable subscribeFills(ExchangeAccount account, Consumer<FillEvent> consumer) {
        log.warn("[ccxt-adapter] subscribeFills NOT IMPLEMENTED: accountId={}", account.getId());
        return () -> {}; // no-op unsubscribe
    }
}

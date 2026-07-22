package com.kwikquant.trading.infrastructure;

import com.kwikquant.account.application.CcxtAuthExchangeFactory;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.PositionSide;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default CcxtOrderAdapter 实现。<strong>4a.1/4a.2 阶段:setLeverage/setMarginMode/createOrder 仍占位
 * (4a.3 真实实现);fetchSnapshot 4a.4 真实实现;subscribeFills 待 spike S2 watchOrders 私有 WS 验证</strong>。
 *
 * <p>4a.2 注入 {@link CcxtAuthExchangeFactory}(鉴权 Exchange 构建,与 BalanceService 共用)+ 待注入
 * {@code CcxtExchangeRegistry}(ccxtSymbol 翻译,4a.3 实装时加)。factory 字段先注入,真实调用在 4a.3。
 *
 * <p>测试场景通过 mock CcxtOrderAdapter bean 覆盖。JaCoCo 已排除本类(外部 API 不可单测)。
 */
@Component
@ConditionalOnMissingBean(name = "ccxtOrderAdapter")
public class DefaultCcxtOrderAdapter implements CcxtOrderAdapter {

    private static final Logger log = LoggerFactory.getLogger(DefaultCcxtOrderAdapter.class);

    // 4a.3/4a.4 真实实现用(鉴权 Exchange 构建,PERP createOrder/setLeverage/fetchPositions 调)。
    private final CcxtAuthExchangeFactory authExchangeFactory;

    @Autowired
    public DefaultCcxtOrderAdapter(CcxtAuthExchangeFactory authExchangeFactory) {
        this.authExchangeFactory = authExchangeFactory;
    }

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
    public void setLeverage(
            ExchangeAccount account, String symbol, int leverage, MarginMode mode, PositionSide posSide) {
        log.warn(
                "[ccxt-adapter] setLeverage NOT IMPLEMENTED (4a.3 pending): accountId={} symbol={} lev={} mode={} posSide={}",
                account.getId(),
                symbol,
                leverage,
                mode,
                posSide);
        throw new ExchangeException("CcxtOrderAdapter.setLeverage not implemented; pending 4a.3", false);
    }

    @Override
    public void setMarginMode(ExchangeAccount account, String symbol, MarginMode mode) {
        log.warn(
                "[ccxt-adapter] setMarginMode NOT IMPLEMENTED (4a.3 pending): accountId={} symbol={} mode={}",
                account.getId(),
                symbol,
                mode);
        throw new ExchangeException("CcxtOrderAdapter.setMarginMode not implemented; pending 4a.3", false);
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

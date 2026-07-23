package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.CreateAccountCommand;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.PaperBalance;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.PaperBalanceMapper;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 模拟盘交易端到端集成测试(Testcontainers PostgreSQL 16)。验证核心链路:
 * 创建模拟盘账户(初始 10 万 USDT)→ 冻结余额(模拟 submit freeze)→ 成交回报 → 余额扣减 + base 入账。
 *
 * <p>不调 TradingService.submit(避开 TradingPairService/CCXT 依赖),直接 balanceService.freeze +
 * executionService.processExecutionReport,专注验证 applyFill 的真 DB 余额变化(ExecutionServiceIntegrationTest
 * 用非模拟盘账户 applyFill noop,没覆盖此路径)。
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class PaperTradingE2EIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ExchangeAccountService accountService;

    @Autowired
    UserMapper userMapper;

    @Autowired
    BalanceService balanceService;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    ExecutionService executionService;

    @Autowired
    PaperBalanceMapper paperBalanceMapper;

    private static final BigDecimal PRICE = new BigDecimal("42000");
    private static final BigDecimal QTY = new BigDecimal("0.1");
    private static final BigDecimal FEE = new BigDecimal("4.2");
    private static final BigDecimal FROZEN = PRICE.multiply(QTY); // 4200 USDT

    private long seedUser() {
        User u = new User();
        u.setUsername("e2e-" + System.nanoTime());
        u.setEmail("e2e-" + System.nanoTime() + "@kwikquant.io");
        u.setPasswordHash("x");
        u.setEnabled(true);
        userMapper.insert(u);
        return u.getId();
    }

    private static TradingPairInfo pair() {
        return new TradingPairInfo(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                "BTC",
                "USDT",
                new BigDecimal("0.0001"),
                new BigDecimal("100"),
                new BigDecimal("0.01"),
                new BigDecimal("0.00000001"),
                true);
    }

    /** insert SUBMITTED 订单(BUY BTC/USDT @42000 qty=0.1,参考所=BINANCE)。 */
    private Order seedSubmittedPaperOrder(long accountId) {
        OrderSubmitCommand cmd = OrderSubmitCommand.spot(
                accountId,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                QTY,
                PRICE,
                null,
                TimeInForce.GTC,
                null,
                "e2e-" + System.nanoTime());
        Order o = Order.create(cmd, pair());
        o.setExchange(Exchange.BINANCE);
        orderMapper.insert(o);
        o.transitionTo(OrderStatus.PENDING_NEW);
        cas(o);
        o.transitionTo(OrderStatus.SUBMITTED);
        cas(o);
        return o;
    }

    private void cas(Order o) {
        int affected = orderMapper.casUpdate(o);
        if (affected == 1) o.setVersion(o.getVersion() + 1);
    }

    @Test
    void paperBuy_fill_deductsQuoteAndCreditsBase() {
        long userId = seedUser();
        ExchangeAccount account = accountService.create(
                new CreateAccountCommand(userId, Exchange.BINANCE, "e2e", null, null, null, true, false));
        long accountId = account.getId();

        // 初始:USDT 10 万
        PaperBalance usdt0 = paperBalanceMapper.findByAccountAndCurrency(accountId, "USDT");
        assertThat(usdt0.getFree()).isEqualByComparingTo("100000");
        assertThat(usdt0.getTotal()).isEqualByComparingTo("100000");

        // 模拟 submit 冻结(BUY 冻 quote=price*qty=4200 USDT)
        balanceService.freeze(accountId, true, "USDT", FROZEN);
        PaperBalance usdt1 = paperBalanceMapper.findByAccountAndCurrency(accountId, "USDT");
        assertThat(usdt1.getFree()).isEqualByComparingTo("95800"); // 100000-4200
        assertThat(usdt1.getUsed()).isEqualByComparingTo("4200");
        assertThat(usdt1.getTotal()).isEqualByComparingTo("100000"); // total 不变

        // 成交回报(price=42000 qty=0.1 fee=4.2 USDT)
        Order order = seedSubmittedPaperOrder(accountId);
        executionService.processExecutionReport(new ExecutionReport(
                order.getId(), UUID.randomUUID().toString(), PRICE, QTY, FEE, "USDT", "taker", Instant.now()));

        // USDT: free=95800-4.2=95795.8, used=4200-4200=0(解冻), total=100000-4204.2=95795.8
        PaperBalance usdtFinal = paperBalanceMapper.findByAccountAndCurrency(accountId, "USDT");
        assertThat(usdtFinal.getFree()).isEqualByComparingTo("95795.8");
        assertThat(usdtFinal.getUsed()).isEqualByComparingTo("0");
        assertThat(usdtFinal.getTotal()).isEqualByComparingTo("95795.8");

        // BTC: free=0.1, total=0.1(成交入账)
        PaperBalance btcFinal = paperBalanceMapper.findByAccountAndCurrency(accountId, "BTC");
        assertThat(btcFinal).isNotNull();
        assertThat(btcFinal.getFree()).isEqualByComparingTo("0.1");
        assertThat(btcFinal.getTotal()).isEqualByComparingTo("0.1");

        // 订单 FILLED
        assertThat(orderMapper.findById(order.getId()).getStatus()).isEqualTo(OrderStatus.FILLED);
    }
}

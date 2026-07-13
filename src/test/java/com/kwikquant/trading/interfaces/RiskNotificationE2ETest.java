package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.infrastructure.RiskDecisionMapper;
import com.kwikquant.risk.infrastructure.RiskPolicyMapper;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end integration test for the risk → notification link (tech-design §3.5 scenarios 1-2).
 *
 * <p>Loads the full Spring context against PostgreSQL (Testcontainers) and drives the real
 * {@link TradingService#submit(OrderSubmitCommand)} → {@link com.kwikquant.risk.application.RiskService}
 * → {@link com.kwikquant.notification.application.NotificationService} → WebSocket push path. Only
 * {@link SimpMessagingTemplate} (WebSocket broker) and {@link TradingPairService} (CCXT network
 * call) are replaced by Mockito mocks; every other bean is the real production instance.
 *
 * <p>A synchronous {@link AsyncConfigurer} forces {@code @Async} listeners to run on the test
 * thread, making assertions deterministic (no Awaitility needed).
 *
 * <p><b>Transactional context:</b> {@link
 * com.kwikquant.notification.application.NotificationService#onRiskTriggered} is annotated
 * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)}. {@link
 * TradingService#submit} is not {@code @Transactional}; the {@code fallbackExecution=true} flag
 * ensures the listener still fires without an active transaction (submit's casUpdate is
 * synchronous auto-commit, so the order is already persisted before the event is published).
 * This test calls {@code submit()} directly (no TransactionTemplate) to mirror the real
 * production path.
 *
 * <p>Scenario 3 (reducing order bypass + RISK_BYPASSED audit on risk-service failure) is omitted
 * here: it is already covered by {@code TradingServiceTest#submitBypassesRiskForPositionReducingSellOnServiceFailure}
 * with a Mockito {@code ArgumentCaptor} on {@code AuditRepository.save}. Re-implementing it at E2E
 * level would require a third {@code @MockitoBean RiskService} (separate Spring context) plus a
 * raw {@code JdbcTemplate} query (the {@code AuditRepository} has no read method) for marginal
 * extra coverage.
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@Import(RiskNotificationE2ETest.SyncAsyncConfig.class)
class RiskNotificationE2ETest extends AbstractIntegrationTest {

    @Autowired
    TradingService tradingService;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    RiskDecisionMapper riskDecisionMapper;

    @Autowired
    RiskPolicyMapper riskPolicyMapper;

    @Autowired
    UserMapper userMapper;

    @Autowired
    ExchangeAccountMapper exchangeAccountMapper;

    @Autowired
    BalanceService balanceService;

    @MockitoBean
    SimpMessagingTemplate messagingTemplate;

    @MockitoBean
    TradingPairService tradingPairService;

    private long testUserId;
    private long testAccountId;

    /** Runs all {@code @Async} methods on the calling thread for deterministic assertions. */
    @TestConfiguration
    static class SyncAsyncConfig implements AsyncConfigurer {
        @Override
        public Executor getAsyncExecutor() {
            return Runnable::run;
        }
    }

    @BeforeEach
    void setUp() {
        // Clear any leftover invocations from a previous test (defensive; @MockitoBean is
        // typically reset by Spring, but clearInvocations is cheap and avoids cross-test bleed
        // when the shared context is reused).
        clearInvocations(messagingTemplate);

        // --- User row: exchange_accounts.user_id has FK REFERENCES users(id) ---
        User user = new User(
                "risk-e2e-" + System.nanoTime(), "risk-e2e-" + System.nanoTime() + "@test.com", "$2a$10$dummyhash");
        userMapper.insert(user);
        testUserId = user.getId();

        // --- Paper-trading exchange account owned by the user ---
        ExchangeAccount account = new ExchangeAccount();
        account.setUserId(testUserId);
        account.setExchange(Exchange.BINANCE);
        account.setLabel("risk-e2e");
        account.setApiKey("test-api-key");
        // Raw dummy bytes: inserted via mapper, bypassing ExchangeAccountService.create encryption.
        account.setApiSecret(new byte[32]);
        account.setNonce(new byte[12]);
        account.setKeyVersion(1);
        account.setPaperTrading(true);
        account.setStatus("ACTIVE");
        exchangeAccountMapper.insert(account);
        testAccountId = account.getId();

        // Paper 账户初始余额 10 万 USDT:TradingService.submit 走 DB paper_balances freeze,
        // 余额 DB 真实化后必须显式 seed,否则 InsufficientBalance(PaperBalanceAdapter.reset)
        balanceService.reset(testAccountId, true);

        // --- MAX_NOTIONAL policy: 100000 USDT cap, enabled ---
        RiskPolicy policy = new RiskPolicy();
        policy.setAccountId(testAccountId);
        policy.setRuleType(RiskRuleType.MAX_NOTIONAL);
        policy.setName("E2E Max Notional");
        policy.setParams(Map.of("maxNotionalUsdt", "100000"));
        policy.setEnabled(true);
        riskPolicyMapper.insert(policy);

        // --- SecurityContext: SecurityUtils.currentUserId() reads Authentication.getName() ---
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(testUserId), "x"));

        // --- TradingPairService is CCXT-backed (network); stub the SPOT pair for BTC/USDT ---
        when(tradingPairService.getPairs(Exchange.BINANCE, MarketType.SPOT)).thenReturn(List.of(btcUsdtPair()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** BTC/USDT SPOT pair on Binance (same shape as ExecutionServiceIntegrationTest). */
    private static TradingPairInfo btcUsdtPair() {
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

    /** Builds a BUY LIMIT order command against the seeded account/pair. */
    private OrderSubmitCommand buyLimit(String suffix, BigDecimal amount, BigDecimal price) {
        return new OrderSubmitCommand(
                testAccountId,
                "BTC/USDT",
                MarketType.SPOT,
                OrderSide.BUY,
                OrderType.LIMIT,
                amount,
                price,
                null,
                TimeInForce.GTC,
                null,
                "e2e-" + suffix + "-" + System.nanoTime());
    }

    /**
     * §3.5 scenario 1: order rejected by risk → WebSocket notification pushed, with the reason
     * carrying only the rule type (M6 desensitisation: threshold values must not leak).
     */
    @Test
    void e2e_orderRejectedByRisk_pushesWebSocketNotificationWithoutThreshold() {
        // notional = 2.0 * 60000 = 120000 > 100000 cap → REJECTED
        OrderSubmitCommand cmd = buyLimit("reject", new BigDecimal("2.0"), new BigDecimal("60000"));

        // P1-2: rule rejection throws RiskRejectedException (→ HTTP 200 + 4105).
        long orderId;
        try {
            tradingService.submit(cmd);
            throw new AssertionError("Expected RiskRejectedException to be thrown");
        } catch (RiskRejectedException ex) {
            orderId = ex.getOrderId();
        }

        // orders table: REJECTED persisted
        Order dbOrder = orderMapper.findById(orderId);
        assertThat(dbOrder).isNotNull();
        assertThat(dbOrder.getStatus()).isEqualTo(OrderStatus.REJECTED);

        // risk_decisions table: REJECTED with MAX_NOTIONAL passed=false in rule_results
        RiskDecision decision = riskDecisionMapper.findByOrderId(orderId);
        assertThat(decision).isNotNull();
        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.REJECTED);
        boolean maxNotionalFailed = decision.getRuleResults().stream()
                .anyMatch(r -> r.ruleType() == RiskRuleType.MAX_NOTIONAL && !r.passed());
        assertThat(maxNotionalFailed).isTrue();

        // WebSocket push to /topic/notifications/{userId}
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/notifications/" + testUserId), payloadCaptor.capture());
        Object rawPayload = payloadCaptor.getValue();
        assertThat(rawPayload).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) rawPayload;
        assertThat(payload.get("type")).isEqualTo("RISK_REJECTED");
        // P1-1: payload carries orderId so the frontend can correlate the rejection
        assertThat(payload.get("orderId")).isEqualTo(orderId);
        // M6: reason must carry the rule type only, never threshold values
        String reason = String.valueOf(payload.get("reason"));
        assertThat(reason).contains("MAX_NOTIONAL");
        assertThat(reason).doesNotContain("60000", "100000", "120000");
    }

    /**
     * §3.5 scenario 2: order approved by risk → routed to paper executor, no RISK_REJECTED
     * WebSocket notification.
     */
    @Test
    void e2e_orderApprovedByRisk_routesToExecutorWithoutRejectionNotification() {
        // notional = 0.5 * 60000 = 30000 < 100000 cap → APPROVED
        OrderSubmitCommand cmd = buyLimit("approve", new BigDecimal("0.5"), new BigDecimal("60000"));

        OrderSubmitResult result = tradingService.submit(cmd);

        assertThat(result).isNotNull();
        // PaperExecutor advances NEW → PENDING_NEW → SUBMITTED synchronously inside submit()
        assertThat(result.status()).isEqualTo(OrderStatus.SUBMITTED);

        // risk_decisions table: APPROVED
        RiskDecision decision = riskDecisionMapper.findByOrderId(result.orderId());
        assertThat(decision).isNotNull();
        assertThat(decision.getVerdict()).isEqualTo(RiskVerdict.APPROVED);

        // No RISK_REJECTED notification pushed (APPROVED does not publish RiskTriggeredEvent)
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/notifications/" + testUserId), any(Object.class));
    }
}

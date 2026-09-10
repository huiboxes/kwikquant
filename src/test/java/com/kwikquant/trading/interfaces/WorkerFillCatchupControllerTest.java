package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.application.FillCatchupRow;
import com.kwikquant.trading.application.FillsSinceResult;
import com.kwikquant.trading.application.TradingService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * {@link WorkerFillCatchupController} 单元测试(纯 Mockito,与 OrderControllerTest 同风格)。
 *
 * <p>鉴权语义与 balance 端点同款:attr 缺失(JWT 误入)→ 400/3001;归属经 getOwned 复核;
 * 账户只从 token 推导,请求侧无 accountId 参数可传(租户收口在契约形状上就不可绕过)。
 */
class WorkerFillCatchupControllerTest {

    private TradingService tradingService;
    private ExchangeAccountService accountService;
    private WorkerFillCatchupController controller;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        accountService = mock(ExchangeAccountService.class);
        controller = new WorkerFillCatchupController(tradingService, accountService);
    }

    private HttpServletRequest workerReq() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("workerAccountId")).thenReturn(7L);
        when(req.getAttribute("workerUserId")).thenReturn(42L);
        return req;
    }

    private ExchangeAccount owned() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(7L);
        acct.setUserId(42L);
        return acct;
    }

    @Test
    void fillsSince_missingWorkerAttrs_throwsIllegalArgument() {
        HttpServletRequest jwtReq = mock(HttpServletRequest.class); // getAttribute 恒 null

        assertThatThrownBy(() -> controller.fillsSince(null, null, jwtReq))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RUNNER X-Worker-Token");
        verifyNoInteractions(tradingService);
    }

    @Test
    void fillsSince_rechecksOwnership_viaGetOwned() {
        when(accountService.getOwned(7L, 42L)).thenThrow(new AccessDeniedException("not owned"));

        assertThatThrownBy(() -> controller.fillsSince(null, null, workerReq()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(tradingService);
    }

    @Test
    void fillsSince_seedMode_nullAfterIdDefaultLimit() {
        when(accountService.getOwned(7L, 42L)).thenReturn(owned());
        when(tradingService.listFillsSince(7L, null, 100)).thenReturn(new FillsSinceResult(List.of(), 55L));

        var resp = controller.fillsSince(null, null, workerReq());

        assertThat(resp.data().fills()).isEmpty();
        assertThat(resp.data().cursor()).isEqualTo(55L); // 播种:当前安全尾部游标
    }

    @Test
    void fillsSince_clampsLimitToOneToTwoHundred() {
        when(accountService.getOwned(7L, 42L)).thenReturn(owned());
        when(tradingService.listFillsSince(eq(7L), anyLong(), anyInt()))
                .thenReturn(new FillsSinceResult(List.of(), 0L));

        controller.fillsSince(10L, 500, workerReq());
        verify(tradingService).listFillsSince(7L, 10L, 200); // 上限封顶

        controller.fillsSince(10L, 0, workerReq());
        verify(tradingService).listFillsSince(7L, 10L, 1); // 下限抬到 1(0/负数无意义)

        controller.fillsSince(10L, 50, workerReq());
        verify(tradingService).listFillsSince(7L, 10L, 50); // 区间内原样
    }

    @Test
    void fillsSince_mapsRowsToDto_lowercaseSideAndNullableEnums() {
        when(accountService.getOwned(7L, 42L)).thenReturn(owned());
        FillCatchupRow perp = new FillCatchupRow(
                12L,
                5L,
                7L,
                "BTC/USDT",
                OrderSide.SELL,
                new BigDecimal("42150.50"),
                new BigDecimal("0.0025"),
                new BigDecimal("0.0052"),
                "USDT",
                "taker",
                Instant.parse("2026-07-04T12:00:05Z"),
                PositionEffect.CLOSE_LONG,
                MarketType.PERP);
        FillCatchupRow legacy = new FillCatchupRow(
                13L,
                6L,
                7L,
                "ETH/USDT",
                OrderSide.BUY,
                new BigDecimal("3000"),
                new BigDecimal("1"),
                new BigDecimal("1"),
                "USDT",
                "maker",
                Instant.parse("2026-07-04T12:00:06Z"),
                null,
                null);
        when(tradingService.listFillsSince(7L, 11L, 100)).thenReturn(new FillsSinceResult(List.of(perp, legacy), 13L));

        var resp = controller.fillsSince(11L, null, workerReq());

        assertThat(resp.data().cursor()).isEqualTo(13L);
        FillCatchupDto first = resp.data().fills().get(0);
        assertThat(first.fillId()).isEqualTo(12L);
        assertThat(first.orderId()).isEqualTo(5L);
        assertThat(first.accountId()).isEqualTo(7L);
        assertThat(first.side()).isEqualTo("sell"); // 小写(FillDto/WS FillEvent 同惯例)
        assertThat(first.price()).isEqualByComparingTo("42150.50"); // JSON 层 @JsonFormat(STRING)
        assertThat(first.positionEffect()).isEqualTo("CLOSE_LONG");
        assertThat(first.marketType()).isEqualTo("PERP");
        assertThat(first.filledAt()).isEqualTo(Instant.parse("2026-07-04T12:00:05Z"));
        FillCatchupDto second = resp.data().fills().get(1);
        assertThat(second.side()).isEqualTo("buy");
        assertThat(second.positionEffect()).isNull(); // SPOT/legacy null-safe
        assertThat(second.marketType()).isNull();
    }
}

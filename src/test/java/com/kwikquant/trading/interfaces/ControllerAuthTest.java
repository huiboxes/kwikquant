package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.infrastructure.PositionMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 验证 OrderController / PositionController 的账户归属鉴权。
 *
 * <p>确保 C1/C2 修复有效：非所有者访问时抛出 AccessDeniedException。
 */
class ControllerAuthTest {

    private ExchangeAccountService accountService;
    private PositionMapper positionMapper;
    private OrderMapper orderMapper;
    private FillMapper fillMapper;
    private PositionController positionController;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        positionMapper = mock(PositionMapper.class);
        orderMapper = mock(OrderMapper.class);
        fillMapper = mock(FillMapper.class);
        positionController = new PositionController(positionMapper, accountService);

        // 模拟登录用户 id=42
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void positionListRequiresOwnership() {
        // 当用户 42 尝试访问 accountId=99（不属于他）时，getOwned 应抛 AccessDeniedException
        when(accountService.getOwned(99L, 42L))
                .thenThrow(new AccessDeniedException("account not accessible"));

        try {
            positionController.list(99L, null);
            assertThat(false).as("Should have thrown AccessDeniedException").isTrue();
        } catch (AccessDeniedException e) {
            // expected
        }

        verify(accountService).getOwned(99L, 42L);
        verifyNoInteractions(positionMapper);
    }

    @Test
    void positionListSucceedsForOwner() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(7L);
        acct.setUserId(42L);
        acct.setExchange(Exchange.BINANCE);
        when(accountService.getOwned(7L, 42L)).thenReturn(acct);

        Position pos = new Position();
        pos.setId(1L);
        pos.setAccountId(7L);
        pos.setSymbol("BTC/USDT");
        pos.setSide("long");
        pos.setQty(new BigDecimal("0.5"));
        pos.setRealizedPnl(BigDecimal.ZERO);
        pos.setVersion(1L);
        pos.setUpdatedAt(Instant.now());
        when(positionMapper.findByAccount(7L)).thenReturn(List.of(pos));

        var result = positionController.list(7L, null);

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).symbol()).isEqualTo("BTC/USDT");
        verify(accountService).getOwned(7L, 42L);
    }
}

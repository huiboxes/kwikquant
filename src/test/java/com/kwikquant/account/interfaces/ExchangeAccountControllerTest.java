package com.kwikquant.account.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.application.ExchangeAccountService.ExchangeAccountView;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.Exchange;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link ExchangeAccountController}.
 *
 * <p>Pure Mockito style (consistent with ControllerAuthTest / RiskPolicyControllerTest).
 * Covers all endpoints: create, list, update, delete.
 */
class ExchangeAccountControllerTest {

    private ExchangeAccountService service;
    private BalanceService balanceService;
    private ExchangeAccountController controller;

    @BeforeEach
    void setUp() {
        service = mock(ExchangeAccountService.class);
        balanceService = mock(BalanceService.class);
        controller = new ExchangeAccountController(service, balanceService);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- create ----

    @Test
    void create_whenValid_returnsCreatedAccount() {
        ExchangeAccount account = new ExchangeAccount();
        account.setId(10L);
        account.setExchange(Exchange.OKX);
        account.setLabel("My OKX");
        account.setApiKey("api-key-123");
        account.setPaperTrading(true);
        account.setStatus("ACTIVE");

        when(service.create(
                        eq(42L), eq(Exchange.OKX), eq("My OKX"), eq("api-key-123"), eq("secret"), eq("pass"), isNull()))
                .thenReturn(account);

        var req = new ExchangeAccountController.CreateAccountRequest(
                Exchange.OKX, "My OKX", "api-key-123", "secret", "pass", null);
        var result = controller.create(req);

        assertThat(result.code()).isEqualTo(0);
        assertThat(result.data().id()).isEqualTo(10L);
        assertThat(result.data().exchange()).isEqualTo(Exchange.OKX);
        assertThat(result.data().label()).isEqualTo("My OKX");
        assertThat(result.data().apiKey()).isEqualTo("api-key-123");
        assertThat(result.data().paperTrading()).isTrue();
        assertThat(result.data().status()).isEqualTo("ACTIVE");
        verify(service).create(42L, Exchange.OKX, "My OKX", "api-key-123", "secret", "pass", null);
    }

    @Test
    void create_withNullPassphrase_delegatesCorrectly() {
        ExchangeAccount account = new ExchangeAccount();
        account.setId(11L);
        account.setExchange(Exchange.BINANCE);
        account.setLabel("Binance Main");
        account.setApiKey("bk-001");
        account.setPaperTrading(false);
        account.setStatus("ACTIVE");

        when(service.create(
                        eq(42L),
                        eq(Exchange.BINANCE),
                        eq("Binance Main"),
                        eq("bk-001"),
                        eq("bs-001"),
                        isNull(),
                        isNull()))
                .thenReturn(account);

        var req = new ExchangeAccountController.CreateAccountRequest(
                Exchange.BINANCE, "Binance Main", "bk-001", "bs-001", null, null);
        var result = controller.create(req);

        assertThat(result.data().id()).isEqualTo(11L);
        verify(service).create(42L, Exchange.BINANCE, "Binance Main", "bk-001", "bs-001", null, null);
    }

    // ---- list ----

    @Test
    void list_returnsUserAccounts() {
        List<ExchangeAccountView> views = List.of(
                new ExchangeAccountView(1L, Exchange.OKX, "OKX", "key1", true, "ACTIVE", null),
                new ExchangeAccountView(2L, Exchange.BINANCE, "Binance", "key2", false, "ACTIVE", null));
        when(service.listByUser(42L)).thenReturn(views);

        var result = controller.list();

        assertThat(result.code()).isEqualTo(0);
        assertThat(result.data()).hasSize(2);
        assertThat(result.data().getFirst().exchange()).isEqualTo(Exchange.OKX);
        assertThat(result.data().get(1).exchange()).isEqualTo(Exchange.BINANCE);
        verify(service).listByUser(42L);
    }

    @Test
    void list_whenEmpty_returnsEmptyList() {
        when(service.listByUser(42L)).thenReturn(List.of());

        var result = controller.list();

        assertThat(result.data()).isEmpty();
    }

    // ---- update ----

    @Test
    void update_whenOwner_returnsUpdatedAccount() {
        ExchangeAccountView updated =
                new ExchangeAccountView(10L, Exchange.OKX, "Updated Label", "new-key", true, "ACTIVE", null);
        when(service.update(eq(10L), eq(42L), eq("Updated Label"), eq("new-key"), eq("new-secret"), eq("new-pass")))
                .thenReturn(updated);

        var req = new ExchangeAccountController.UpdateAccountRequest(
                "Updated Label", "new-key", "new-secret", "new-pass");
        var result = controller.update(10L, req);

        assertThat(result.code()).isEqualTo(0);
        assertThat(result.data().label()).isEqualTo("Updated Label");
        assertThat(result.data().apiKey()).isEqualTo("new-key");
        verify(service).update(10L, 42L, "Updated Label", "new-key", "new-secret", "new-pass");
    }

    // ---- delete ----

    @Test
    void delete_whenOwner_deletesSuccessfully() {
        var result = controller.delete(10L);

        assertThat(result.code()).isEqualTo(0);
        assertThat(result.data()).isNull();
        verify(service).delete(10L, 42L);
    }
}

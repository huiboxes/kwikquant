package com.kwikquant.account.interfaces;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.application.ExchangeAccountService.ExchangeAccountView;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.Exchange;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
class ExchangeAccountController {

    private final ExchangeAccountService service;
    private final BalanceService balanceService;

    ExchangeAccountController(ExchangeAccountService service, BalanceService balanceService) {
        this.service = service;
        this.balanceService = balanceService;
    }

    @PostMapping
    public ApiResponse<ExchangeAccountView> create(@Valid @RequestBody CreateAccountRequest req) {
        var account = service.create(
                SecurityUtils.currentUserId(),
                req.exchange(),
                req.label(),
                req.apiKey(),
                req.apiSecret(),
                req.passphrase());
        var view = new ExchangeAccountView(
                account.getId(),
                account.getExchange(),
                account.getLabel(),
                account.getApiKey(),
                account.isPaperTrading(),
                account.getStatus());
        return ApiResponse.ok(view, traceId());
    }

    @GetMapping
    public ApiResponse<List<ExchangeAccountView>> list() {
        return ApiResponse.ok(service.listByUser(SecurityUtils.currentUserId()), traceId());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(null, traceId());
    }

    @PutMapping("/{id}")
    public ApiResponse<ExchangeAccountView> update(
            @PathVariable long id, @Valid @RequestBody UpdateAccountRequest req) {
        var view = service.update(
                id, SecurityUtils.currentUserId(), req.label(), req.apiKey(), req.apiSecret(), req.passphrase());
        return ApiResponse.ok(view, traceId());
    }

    @GetMapping("/{id}/balance")
    public ApiResponse<BalanceSnapshot> balance(@PathVariable long id) {
        BalanceSnapshot snapshot = balanceService.fetchBalance(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(snapshot, traceId());
    }

    private static String traceId() {
        return MDC.get("traceId");
    }

    record CreateAccountRequest(
            @NotNull Exchange exchange,
            @NotBlank String label,
            @NotBlank String apiKey,
            @NotBlank String apiSecret,
            String passphrase) {}

    record UpdateAccountRequest(
            @NotBlank String label, @NotBlank String apiKey, @NotBlank String apiSecret, String passphrase) {}
}

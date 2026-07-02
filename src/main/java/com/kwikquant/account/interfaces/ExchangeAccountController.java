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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    // label 会作为 audit 记录的 targetId 写入审计日志（{@code @Auditable(targetId="#label")}），
    // 白名单只允许字母/数字/空格/下划线/中划线，防止用户误把 API key 前缀/邮箱当 label 而被审计日志固化。
    // 与 LlmApiKeyController.CreateLlmKeyRequest 对齐（Round 4 一致性）。
    private static final String LABEL_PATTERN = "^[A-Za-z0-9 _-]{1,100}$";

    record CreateAccountRequest(
            @NotNull Exchange exchange,
            @NotBlank @Size(min = 1, max = 100) @Pattern(regexp = LABEL_PATTERN) String label,
            @NotBlank String apiKey,
            @NotBlank String apiSecret,
            String passphrase) {}

    record UpdateAccountRequest(
            @NotBlank @Size(min = 1, max = 100) @Pattern(regexp = LABEL_PATTERN) String label,
            @NotBlank String apiKey,
            @NotBlank String apiSecret,
            String passphrase) {}
}

package com.kwikquant.account.interfaces;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.application.CreateAccountCommand;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.application.ExchangeAccountService.ExchangeAccountView;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.LabelPatterns;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.Exchange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "交易所账户")
class ExchangeAccountController {

    private final ExchangeAccountService service;
    private final BalanceService balanceService;

    ExchangeAccountController(ExchangeAccountService service, BalanceService balanceService) {
        this.service = service;
        this.balanceService = balanceService;
    }

    @PostMapping
    @Operation(
            summary = "创建交易所账户",
            description = "需 JWT 鉴权。API key 端到端加密存储（AES-256-GCM），响应中 apiKey 字段脱敏返回（仅后缀），完整 key 不出后端。"
                    + "label 重复或格式非法返回 400（3001）。实盘（paperTrading=false）必须提供 apiKey/apiSecret，"
                    + "否则返回 400（3001）；exchange 不接受 PAPER。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "参数非法或 label 重复（3001 VALIDATION_FAILED）")
    public ApiResponse<ExchangeAccountView> create(@Valid @RequestBody CreateAccountRequest req) {
        var account = service.create(new CreateAccountCommand(
                SecurityUtils.currentUserId(),
                req.exchange(),
                req.label(),
                req.apiKey(),
                req.apiSecret(),
                req.passphrase(),
                req.paperTrading()));
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
    @Operation(summary = "查询当前用户交易所账户列表", description = "需 JWT 鉴权。仅返回当前用户名下账户，apiKey 脱敏。")
    public ApiResponse<List<ExchangeAccountView>> list() {
        return ApiResponse.ok(service.listByUser(SecurityUtils.currentUserId()), traceId());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除交易所账户", description = "需 JWT 鉴权。仅可删除本人账户；越权访问他人账户返回 403（1002）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "越权访问他人账户（1002 FORBIDDEN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "账户不存在（4001 RESOURCE_NOT_FOUND）")
    public ApiResponse<Void> delete(@Parameter(description = "账户 ID", example = "42") @PathVariable long id) {
        service.delete(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(null, traceId());
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新交易所账户", description = "需 JWT 鉴权。可更新 label / API key / passphrase，仅可操作本人账户。响应 apiKey 脱敏。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "越权访问他人账户（1002 FORBIDDEN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "账户不存在（4001 RESOURCE_NOT_FOUND）")
    public ApiResponse<ExchangeAccountView> update(
            @Parameter(description = "账户 ID", example = "42") @PathVariable long id,
            @Valid @RequestBody UpdateAccountRequest req) {
        var view = service.update(
                id, SecurityUtils.currentUserId(), req.label(), req.apiKey(), req.apiSecret(), req.passphrase());
        return ApiResponse.ok(view, traceId());
    }

    @GetMapping("/{id}/balance")
    @Operation(summary = "查询账户余额", description = "需 JWT 鉴权。实时拉取交易所余额快照。仅可操作本人账户。" + "交易所不可用返回 502（6001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "越权访问他人账户（1002 FORBIDDEN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "账户不存在（4001 RESOURCE_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用（6001 EXCHANGE_UNAVAILABLE）")
    public ApiResponse<BalanceSnapshot> balance(
            @Parameter(description = "账户 ID", example = "42") @PathVariable long id) {
        BalanceSnapshot snapshot = balanceService.fetchBalance(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(snapshot, traceId());
    }

    private static String traceId() {
        return MDC.get("traceId");
    }

    // label 会作为 audit 记录的 targetId 写入审计日志（{@code @Auditable(targetId="#label")}），
    // 白名单只允许字母/数字/空格/下划线/中划线，防止用户误把 API key 前缀/邮箱当 label 而被审计日志固化。
    // 与 LlmApiKeyController.CreateLlmKeyRequest 对齐（Round 4 一致性），共享定义见 LabelPatterns。
    private static final String LABEL_PATTERN = LabelPatterns.LABEL_100;

    record CreateAccountRequest(
            @Schema(
                            description = "参考交易所（枚举: BINANCE | OKX | BITGET）。仅表示撮合/定价参考哪个交易所的公开行情，"
                                    + "不表示是否模拟盘——是否模拟盘由 paperTrading 字段决定，本字段不接受 PAPER。",
                            example = "BINANCE",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    Exchange exchange,
            @Schema(
                            description = "账户标签，1-100 字符，仅字母/数字/空格/_/-",
                            example = "主账户",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(min = 1, max = 100)
                    @Pattern(regexp = LABEL_PATTERN)
                    String label,
            @Schema(
                            description = "是否模拟盘。true 时 apiKey/apiSecret 可不填（撮合走 exchange 字段指向的公开行情，"
                                    + "不需要鉴权）；false（实盘）时 apiKey/apiSecret 必填。",
                            example = "true",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    Boolean paperTrading,
            @Schema(description = "交易所 API key（端到端加密存储，零提现权限建议）。模拟盘（paperTrading=true）可不填。", example = "abc123key")
                    String apiKey,
            @Schema(description = "交易所 API secret（加密存储，不出现在响应中）。模拟盘（paperTrading=true）可不填。", example = "secretXYZ")
                    String apiSecret,
            @Schema(description = "OKX 等交易所需要的 passphrase，无则不传", example = "pass123") String passphrase) {}

    record UpdateAccountRequest(
            @Schema(description = "账户标签", example = "主账户", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(min = 1, max = 100)
                    @Pattern(regexp = LABEL_PATTERN)
                    String label,
            @Schema(description = "新 API key（加密存储）", example = "abc123key", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    String apiKey,
            @Schema(
                            description = "新 API secret（加密存储）",
                            example = "secretXYZ",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    String apiSecret,
            @Schema(description = "新 passphrase，无则不传", example = "pass123") String passphrase) {}
}

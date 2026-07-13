package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.application.StrategyLifecycleService;
import com.kwikquant.strategy.domain.StrategyDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 策略 CRUD + 生命周期 REST 端点。所有端点经 JWT 鉴权，userId 来自 SecurityContext。
 */
@RestController
@RequestMapping("/api/v1/strategies")
@Tag(name = "策略")
class StrategyController {

    private final StrategyCrudService crudService;
    private final StrategyLifecycleService lifecycleService;

    StrategyController(StrategyCrudService crudService, StrategyLifecycleService lifecycleService) {
        this.crudService = crudService;
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
    @Operation(summary = "创建策略", description = "需 JWT 鉴权。创建处于 DRAFT 状态的策略。参数非法返回 400（3001）。")
    public ApiResponse<StrategyDetailDto> create(@Valid @RequestBody CreateStrategyRequest req) {
        long userId = SecurityUtils.currentUserId();
        StrategyDefinition s = crudService.create(
                userId,
                req.name(),
                req.description(),
                req.symbol(),
                req.exchange(),
                req.marketType(),
                req.intervalValue(),
                req.parameters());
        return ApiResponse.ok(StrategyDetailDto.from(s));
    }

    @GetMapping
    @Operation(summary = "查询当前用户策略列表", description = "需 JWT 鉴权。仅返回当前用户名下策略。")
    public ApiResponse<List<StrategyDetailDto>> list() {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(crudService.listByUser(userId).stream()
                .map(StrategyDetailDto::from)
                .toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "查策略详情", description = "需 JWT 鉴权。策略不存在或非本人返回 404（7001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在或不属于当前用户（7001 STRATEGY_NOT_FOUND）")
    public ApiResponse<StrategyDetailDto> get(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(crudService.getOwned(id, SecurityUtils.currentUserId())));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新策略", description = "需 JWT 鉴权。仅 DRAFT 状态可改；状态不可改返回 409（7002），不存在或非本人返回 409（4009）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "状态不可转移（7002）或策略不存在/非本人（4009 STATE_CONFLICT）")
    public ApiResponse<StrategyDetailDto> update(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long id,
            @Valid @RequestBody UpdateStrategyRequest req) {
        StrategyDefinition s = crudService.update(
                id,
                SecurityUtils.currentUserId(),
                req.name(),
                req.description(),
                req.symbol(),
                req.exchange(),
                req.marketType(),
                req.intervalValue(),
                req.parameters());
        return ApiResponse.ok(StrategyDetailDto.from(s));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除策略", description = "需 JWT 鉴权。策略不存在或非本人返回 409（4009）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "策略不存在/非本人（4009 STATE_CONFLICT）")
    public ApiResponse<Void> delete(@Parameter(description = "策略 ID", example = "128") @PathVariable long id) {
        crudService.delete(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/ready")
    @Operation(summary = "标记策略就绪", description = "需 JWT 鉴权。DRAFT→READY 转移。无发布代码返回 409（7006）；状态不可转移返回 409（7002）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "无发布代码（7006）或状态不可转移（7002/4009）")
    public ApiResponse<StrategyDetailDto> ready(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(lifecycleService.ready(id, SecurityUtils.currentUserId())));
    }

    @PostMapping("/{id}/start")
    @Operation(
            summary = "启动策略",
            description = "需 JWT 鉴权。READY|PAUSED→RUNNING 转移（PAUSED→RUNNING 即 resume，复用同一端点，无独立 resume 端点），需有发布代码。"
                    + "无发布代码返回 409（7006）；状态不可转移返回 409（7002）；Worker 启动失败返回 500（7200）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "无发布代码（7006）或状态不可转移（7002/4009）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "Worker 启动失败（7200 WORKER_START_FAILED）")
    public ApiResponse<StrategyDetailDto> start(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(lifecycleService.start(id, SecurityUtils.currentUserId())));
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "停止策略", description = "需 JWT 鉴权。RUNNING/PAUSED/ERROR→STOPPED 转移。状态不可转移返回 409（7002）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "状态不可转移（7002/4009）")
    public ApiResponse<StrategyDetailDto> stop(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(lifecycleService.stop(id, SecurityUtils.currentUserId())));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "暂停策略", description = "需 JWT 鉴权。RUNNING→PAUSED 转移。状态不可转移返回 409（7002）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "状态不可转移（7002/4009）")
    public ApiResponse<StrategyDetailDto> pause(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(lifecycleService.pause(id, SecurityUtils.currentUserId())));
    }

    record CreateStrategyRequest(
            @Schema(description = "策略名称，≤200 字符", example = "BTC 网格", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 200)
                    String name,
            @Schema(description = "策略描述，≤2000 字符", example = "区间网格低买高卖") @Size(max = 2000) String description,
            @Schema(description = "canonical symbol", example = "BTC/USDT", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 20)
                    String symbol,
            @Schema(
                            description = "交易所（枚举: BINANCE | OKX | BYBIT | PAPER）",
                            example = "BINANCE",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 20)
                    String exchange,
            @Schema(description = "市场类型（枚举: SPOT | FUTURES）", example = "SPOT") @Size(max = 10) String marketType,
            @Schema(description = "K 线周期（枚举: 1m|5m|15m|1h|4h|1d 等）", example = "1h") @Size(max = 10)
                    String intervalValue,
            @Schema(description = "策略参数（JSON 字符串）", example = "{\"gridNum\":10}") String parameters) {}

    record UpdateStrategyRequest(
            @Schema(description = "策略名称", example = "BTC 网格", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 200)
                    String name,
            @Schema(description = "策略描述", example = "区间网格低买高卖") @Size(max = 2000) String description,
            @Schema(description = "canonical symbol", example = "BTC/USDT", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 20)
                    String symbol,
            @Schema(description = "交易所", example = "BINANCE", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 20)
                    String exchange,
            @Schema(description = "市场类型", example = "SPOT") @Size(max = 10) String marketType,
            @Schema(description = "K 线周期", example = "1h") @Size(max = 10) String intervalValue,
            @Schema(description = "策略参数（JSON 字符串）", example = "{\"gridNum\":10}") String parameters) {}

    record StrategyDetailDto(
            @Schema(description = "策略 ID", example = "128") Long id,
            @Schema(description = "策略名称", example = "BTC 网格") String name,
            @Schema(description = "策略描述") String description,
            @Schema(description = "canonical symbol", example = "BTC/USDT") String symbol,
            @Schema(description = "交易所", example = "BINANCE") String exchange,
            @Schema(description = "市场类型", example = "SPOT") String marketType,
            @Schema(description = "K 线周期", example = "1h") String intervalValue,
            @Schema(description = "策略状态（枚举: DRAFT | READY | RUNNING | PAUSED | STOPPED | ERROR）", example = "RUNNING")
                    StrategyStatus status,
            @Schema(description = "策略参数（JSON 字符串）", example = "{\"gridNum\":10}") String parameters,
            @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt,
            @Schema(description = "最后更新时间", example = "2026-07-04T12:00:00Z") Instant updatedAt) {
        static StrategyDetailDto from(StrategyDefinition s) {
            return new StrategyDetailDto(
                    s.getId(),
                    s.getName(),
                    s.getDescription(),
                    s.getSymbol(),
                    s.getExchange(),
                    s.getMarketType(),
                    s.getIntervalValue(),
                    s.getStatus(),
                    s.getParameters(),
                    s.getCreatedAt(),
                    s.getUpdatedAt());
        }
    }
}

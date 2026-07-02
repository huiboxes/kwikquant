package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.application.StrategyLifecycleService;
import com.kwikquant.strategy.domain.StrategyDefinition;
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
class StrategyController {

    private final StrategyCrudService crudService;
    private final StrategyLifecycleService lifecycleService;

    StrategyController(StrategyCrudService crudService, StrategyLifecycleService lifecycleService) {
        this.crudService = crudService;
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
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
    public ApiResponse<List<StrategyDetailDto>> list() {
        long userId = SecurityUtils.currentUserId();
        return ApiResponse.ok(crudService.listByUser(userId).stream()
                .map(StrategyDetailDto::from)
                .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<StrategyDetailDto> get(@PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(crudService.getOwned(id, SecurityUtils.currentUserId())));
    }

    @PutMapping("/{id}")
    public ApiResponse<StrategyDetailDto> update(@PathVariable long id, @Valid @RequestBody UpdateStrategyRequest req) {
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
    public ApiResponse<Void> delete(@PathVariable long id) {
        crudService.delete(id, SecurityUtils.currentUserId());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/ready")
    public ApiResponse<StrategyDetailDto> ready(@PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(lifecycleService.ready(id, SecurityUtils.currentUserId())));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<StrategyDetailDto> start(@PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(lifecycleService.start(id, SecurityUtils.currentUserId())));
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<StrategyDetailDto> stop(@PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(lifecycleService.stop(id, SecurityUtils.currentUserId())));
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<StrategyDetailDto> pause(@PathVariable long id) {
        return ApiResponse.ok(StrategyDetailDto.from(lifecycleService.pause(id, SecurityUtils.currentUserId())));
    }

    record CreateStrategyRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 20) String symbol,
            @NotBlank @Size(max = 20) String exchange,
            @Size(max = 10) String marketType,
            @Size(max = 10) String intervalValue,
            String parameters) {}

    record UpdateStrategyRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 20) String symbol,
            @NotBlank @Size(max = 20) String exchange,
            @Size(max = 10) String marketType,
            @Size(max = 10) String intervalValue,
            String parameters) {}

    record StrategyDetailDto(
            Long id,
            String name,
            String description,
            String symbol,
            String exchange,
            String marketType,
            String intervalValue,
            StrategyStatus status,
            String parameters,
            Instant createdAt,
            Instant updatedAt) {
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

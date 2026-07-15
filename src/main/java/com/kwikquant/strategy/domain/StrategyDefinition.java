package com.kwikquant.strategy.domain;

import com.kwikquant.shared.types.StrategyStatus;
import java.time.Instant;

/**
 * 策略定义聚合根。封装策略元数据 + StrategyStatus 状态机。
 *
 * <p>状态推进通过 {@link #transitionTo(StrategyStatus)} 进行。状态机校验失败抛 {@link
 * IllegalStrategyStateTransitionException}。
 *
 * <p>本类是富领域对象，保留传统 getter/setter（与项目现有实体风格一致，无 Lombok）。
 */
public class StrategyDefinition {

    private static final String DEFAULT_MARKET_TYPE = "SPOT";
    private static final String DEFAULT_INTERVAL = "1h";

    private Long id;
    private long userId;
    private String name;
    private String description;
    private String symbol;
    private String exchange;
    private String marketType;
    private String intervalValue;
    private StrategyStatus status;
    private String parameters;
    private String version;
    private boolean deleted;
    private Instant createdAt;
    private Instant updatedAt;

    public StrategyDefinition() {}

    /**
     * 工厂方法：创建 DRAFT 状态的策略定义。
     *
     * @param userId 所属用户 ID
     * @param name 策略名称
     * @param description 策略描述
     * @param symbol 交易对（CCXT 格式，如 BTC/USDT）
     * @param exchange 交易所标识
     * @param marketType 市场类型（默认 SPOT）
     * @param intervalValue K线周期（默认 1h）
     * @param parameters 策略参数 JSON
     * @return DRAFT 状态的新策略定义
     */
    public static StrategyDefinition create(
            long userId,
            String name,
            String description,
            String symbol,
            String exchange,
            String marketType,
            String intervalValue,
            String parameters) {
        StrategyDefinition s = new StrategyDefinition();
        s.userId = userId;
        s.name = name;
        s.description = description;
        s.symbol = symbol;
        s.exchange = exchange;
        s.marketType = marketType != null ? marketType : DEFAULT_MARKET_TYPE;
        s.intervalValue = intervalValue != null ? intervalValue : DEFAULT_INTERVAL;
        s.status = StrategyStatus.DRAFT;
        s.parameters = parameters != null ? parameters : "{}";
        s.deleted = false;
        return s;
    }

    /**
     * 状态推进。违反状态机抛异常。
     *
     * <p><strong>仅更新内存对象，DB 写入由 Service 层事务内完成。</strong>
     *
     * @param target 目标状态
     * @throws IllegalStrategyStateTransitionException 非法状态转换时抛出
     */
    public void transitionTo(StrategyStatus target) {
        if (status == null || !status.canTransitionTo(target)) {
            throw new IllegalStrategyStateTransitionException(status, target);
        }
        this.status = target;
    }

    // ---------- getters / setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getMarketType() {
        return marketType;
    }

    public void setMarketType(String marketType) {
        this.marketType = marketType;
    }

    public String getIntervalValue() {
        return intervalValue;
    }

    public void setIntervalValue(String intervalValue) {
        this.intervalValue = intervalValue;
    }

    public StrategyStatus getStatus() {
        return status;
    }

    public void setStatus(StrategyStatus status) {
        this.status = status;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}

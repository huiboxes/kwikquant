package com.kwikquant.shared.types;

import java.math.BigDecimal;

/**
 * 交易对规格快照(订单接受性纯函数 {@link OrderAcceptance} 的输入,docs/matching-spec.md §9.1)。
 *
 * <p><b>单位契约</b>:全部币单位(base coin)——PERP 的 minQty/stepSize 在装载时已币化
 * (见 {@code market/domain/TradingPairInfo}),接受性层零换算。张数只存在于交易所边界。
 *
 * <p>与 {@code TradingPairInfo} 的关系:本 record 是其接受性相关字段的子集,放 shared 供
 * trading(Order.validate)与 strategy(回测 pairSpecs 快照下发)共用而不引入 market 依赖;
 * 转换由调用方完成(shared 在依赖白名单最底层,不能反向引用 market)。
 *
 * @param symbol        canonical symbol(base/quote)
 * @param marketType    市场类型
 * @param minQty        最小下单量,币单位(null = 交易所未声明,不限)
 * @param maxQty        最大下单量,币单位(null = 不限)
 * @param tickSize      价格最小变动,quote 计价(null/≤0 = 不做对齐校验)
 * @param stepSize      数量步长,币单位(null/≤0 = 不做对齐校验)
 * @param maxLeverage   per-symbol 杠杆上限(仅 PERP;null = 未声明,PERP fail-closed 拒单)
 */
public record PairSpec(
        String symbol,
        MarketType marketType,
        BigDecimal minQty,
        BigDecimal maxQty,
        BigDecimal tickSize,
        BigDecimal stepSize,
        Integer maxLeverage) {}

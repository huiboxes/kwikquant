package com.kwikquant.market.infrastructure;

/**
 * 行情采集 worker 的生命周期接口。
 *
 * <p>设计 §5.1 原将其定义为 {@code MarketDataService} 的 nested interface，但 Worker implements 它、
 * MarketDataService 又依赖 Worker，构成 T7↔T8 循环依赖。提为顶层接口解耦，语义不变。
 */
public interface Stoppable {

    /** 启动 worker（在 Virtual Thread 上跑采集循环）。幂等：重复调用无副作用。 */
    void start();

    /** 停止 worker（中断采集线程）。幂等：重复调用无副作用。 */
    void stop();
}

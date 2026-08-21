package com.kwikquant.strategy.domain;

/**
 * 回测失败分类。worker 非 0 退出时按 exit code + stderr 关键字归类,
 * 前端按分类展示用户可读文案 + 可行动建议(替代裸 stderr 透传)。
 *
 * <p>分类覆盖率目标 ~80%,未识别归 {@link #INTERNAL}(technicalDetail 兜底排错)。
 */
public enum BacktestFailureCategory {

    /** 运行环境缺失:解释器/依赖/worker 脚本不可用(spawn failed、ModuleNotFoundError 等)。 */
    ENV_SETUP,
    /** 回测区间无历史数据(worker exit 2 / NO_MARKET_DATA 标记 / 行情拉取 5001)。 */
    MARKET_DATA,
    /** 策略代码自身错误(SyntaxError/NameError/TypeError 等用户可修复类)。 */
    STRATEGY_CODE,
    /** 资源配额:OOM(exit 137)/MemoryError/并发配额超限。 */
    QUOTA,
    /** worker 执行超时。 */
    TIMEOUT,
    /** 未识别的平台内部错误,technicalDetail 透出供排错。 */
    INTERNAL;

    /**
     * 分类对应的用户可读文案(产品层,不裸透 stderr)。REST 任务 DTO 与 WS FAILED 事件共用此映射,
     * 保证轮询兜底与即时推送两条路径文案一致。{@link #INTERNAL}(未识别)返 null,由前端兜底透出原始原因。
     */
    public String userMessage() {
        return switch (this) {
            case ENV_SETUP -> "回测运行环境未就绪(Python 依赖或配置缺失)，请联系管理员或检查后端日志";
            case MARKET_DATA -> "所选区间暂无历史行情数据，请调整回测区间或标的后重试";
            case STRATEGY_CODE -> "策略代码运行出错，请检查语法与 API 调用，修复并发布新版本后重试";
            case QUOTA -> "回测资源超限(内存或并发配额)，请缩短回测区间或稍后重试";
            case TIMEOUT -> "回测执行超时，请缩短回测区间或稍后重试";
            case INTERNAL -> null;
        };
    }

    /**
     * 由失败描述文本归类(errorMessage 含 exit code + stderr 摘要;大小写不敏感匹配)。
     *
     * @param combined 失败描述(runner 异常 message / stderr),null 安全
     */
    public static BacktestFailureCategory classify(String combined) {
        if (combined == null || combined.isBlank()) {
            return INTERNAL;
        }
        String s = combined.toLowerCase();
        if (s.contains("timeout") || s.contains("timed out")) {
            return TIMEOUT;
        }
        if (s.contains("no_market_data") || s.contains("无历史数据") || s.contains("5001")) {
            return MARKET_DATA;
        }
        if (s.contains("spawn failed")
                || s.contains("cannot run program")
                || s.contains("modulenotfounderror")
                || s.contains("importerror")
                || s.contains("no such file")
                || s.contains("未配置")) {
            return ENV_SETUP;
        }
        if (s.contains("exit 137") || s.contains("memoryerror") || s.contains("quota") || s.contains("配额")) {
            return QUOTA;
        }
        if (s.contains("syntaxerror")
                || s.contains("indentationerror")
                || s.contains("nameerror")
                || s.contains("attributeerror")
                || s.contains("typeerror")
                || s.contains("zerodivisionerror")) {
            return STRATEGY_CODE;
        }
        return INTERNAL;
    }
}

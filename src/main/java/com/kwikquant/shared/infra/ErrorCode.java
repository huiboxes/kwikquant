package com.kwikquant.shared.infra;

/**
 * 全局错误码。分段约定（避免子模块 code 空间冲突）：
 *
 * <pre>
 *   0xxx  成功
 *   1xxx  认证 / 授权（1001 UNAUTH、1002 FORBIDDEN）
 *   20xx  风控通用（2001 REJECTED、2002 INSUFFICIENT_MARGIN、201x 策略级）
 *   3xxx  参数校验
 *   4xxx  通用资源（4001 NOT_FOUND、4002 IDEMPOTENCY、4009 STATE_CONFLICT、4029 RATE_LIMITED）
 *   41xx  Trading order 域
 *   5xxx  服务端内部错误
 *   6xxx  外部服务（交易所）
 *   70xx  Strategy 域
 *   71xx  Backtest 域
 *   72xx  Worker 编排（7200 START、7201 NOT_RUNNING、7202 HEALTH — 后两者 Wave 8 才会启用）
 *   80xx  AI/LLM 网关
 *   90xx  Report 域
 *   10xxx MCP 域（10001 TOKEN_INVALID、10002 TOOL_PARAM_INVALID、10003 BACKTEST_TIMEOUT、10004 EMERGENCY_CONFIRM_REQUIRED）
 * </pre>
 */
public final class ErrorCode {
    public static final int SUCCESS = 0;
    public static final int UNAUTHENTICATED = 1001;
    public static final int FORBIDDEN = 1002;
    public static final int RISK_REJECTED = 2001;
    public static final int INSUFFICIENT_MARGIN = 2002;
    public static final int VALIDATION_FAILED = 3001;
    /** 邀请码无效(不存在/已禁用/已过期/已用尽),注册门禁。 */
    public static final int INVITE_CODE_INVALID = 3002;

    public static final int RESOURCE_NOT_FOUND = 4001;
    public static final int IDEMPOTENCY_CONFLICT = 4002;
    public static final int RESOURCE_STATE_CONFLICT = 4009;
    public static final int RATE_LIMITED = 4029;
    public static final int INTERNAL_ERROR = 5001;
    public static final int SERVICE_OVERLOADED = 5031;
    public static final int EXCHANGE_UNAVAILABLE = 6001;

    // Trading 模块 41xx 段
    public static final int ORDER_NOT_FOUND = 4100;
    public static final int ORDER_ILLEGAL_STATE_TRANSITION = 4101;
    public static final int ORDER_INSUFFICIENT_BALANCE = 4102;
    public static final int ORDER_INVALID_PARAMS = 4103;
    public static final int ORDER_EXCHANGE_REJECTED = 4104;
    public static final int ORDER_RISK_REJECTED = 4105;
    public static final int ORDER_MATCHING_FAILED = 4106;
    public static final int ORDER_CONCURRENCY_CONFLICT = 4107;
    public static final int ORDER_EXCHANGE_API_ERROR = 4108;

    // Risk 模块 20xx 段
    public static final int RISK_POLICY_NOT_FOUND = 2010;
    public static final int RISK_POLICY_CONFLICT = 2011;

    // Strategy 模块 70xx 段
    public static final int STRATEGY_NOT_FOUND = 7001;
    public static final int STRATEGY_ILLEGAL_STATE_TRANSITION = 7002;
    public static final int STRATEGY_ALREADY_DELETED = 7003;
    public static final int STRATEGY_CODE_NOT_FOUND = 7004;
    public static final int STRATEGY_CODE_ILLEGAL_STATE = 7005;
    public static final int STRATEGY_NO_PUBLISHED_CODE = 7006;

    // Backtest 71xx 段
    public static final int BACKTEST_TASK_NOT_FOUND = 7100;
    public static final int BACKTEST_ALREADY_RUNNING = 7101;
    public static final int BACKTEST_SUBMISSION_FAILED = 7102;

    // Worker 72xx 段（RESERVED for Wave 8：WORKER_NOT_RUNNING / WORKER_HEALTH_CHECK_FAILED 当前只在
    // 内部日志里出现，不透传给客户端；避免误占码段，先按 code-impl §9 保留位）
    public static final int WORKER_START_FAILED = 7200;
    public static final int WORKER_NOT_RUNNING = 7201;
    public static final int WORKER_HEALTH_CHECK_FAILED = 7202;

    // Wave 8 73xx 段（回测下单 + service token + runner 失败；7201/7202 已被 Worker 段占用,故用 73xx）
    public static final int BACKTEST_RUNNER_FAILED = 7300;
    public static final int WORKER_TOKEN_INVALID = 7301;
    public static final int BACKTEST_ORDER_REJECTED = 7302;
    public static final int BACKTEST_TASK_NOT_RUNNING = 7303;
    /** 回测区间无历史数据(worker 拉空 → exit 2 → markFailed),§6 错误协议。 */
    public static final int BACKTEST_NO_MARKET_DATA = 7304;
    /**
     * 回测不支持该市场类型(阶段2g §11 M10-new:回测 PERP 留账阶段6+,BacktestOrderService 拒 PERP 单)。
     * <p>spec §11 M10-new 原文"返 4001"系笔误(4001=NOT_FOUND 语义不符),架构师拍板新加 7305(73xx 段,语义清晰,前端可按 code 区分"PERP 不支持"vs"余额不足")。
     */
    public static final int BACKTEST_UNSUPPORTED_MARKET_TYPE = 7305;

    // AI Gateway 8xxx 段（8001 RESERVED：LLM_KEY_NOT_FOUND 删除——key 不存在/非本人走通用 4001/4003；
    //                       8005 LLM_CONTEXT_TOO_LONG RESERVED：Wave 8 上下文修剪落地时启用）
    public static final int LLM_KEY_INVALID_PROVIDER = 8002;
    public static final int LLM_PROVIDER_ERROR = 8003;
    public static final int LLM_STREAM_INTERRUPTED = 8004;
    public static final int LLM_CONTEXT_TOO_LONG = 8005;

    // Report 模块 90xx 段
    public static final int REPORT_NOT_FOUND = 9001;
    public static final int REPORT_INVALID_PAYLOAD = 9002;
    public static final int REPORT_COMPARISON_INSUFFICIENT = 9003;
    public static final int REPORT_EXPORT_FAILED = 9004;

    // MCP 模块 10xxx 段（Wave 10）
    /** PAT 无效/已吊销/已过期，filter 层 401。 */
    public static final int MCP_TOKEN_INVALID = 10001;
    /** MCP 工具入参非法（exchange/ruleType 枚举值不合法等），controller 层 400。 */
    public static final int MCP_TOOL_PARAM_INVALID = 10002;
    /** run_backtest 60s 轮询超时（保留码，Step 5 启用）。 */
    public static final int MCP_BACKTEST_TIMEOUT = 10003;
    /** emergency_stop / start_live_trading 缺 confirm=true 二次确认（保留码，Step 5/7 启用）。 */
    public static final int MCP_EMERGENCY_CONFIRM_REQUIRED = 10004;

    private ErrorCode() {}
}

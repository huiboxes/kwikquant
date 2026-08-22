package com.kwikquant.shared.infra;

/**
 * 全局错误码。分段约定(避免子模块 code 空间冲突):
 *
 * <pre>
 *   0xxx  成功
 *   1xxx  认证 / 授权(1001 UNAUTH、1002 FORBIDDEN)
 *   20xx  风控通用(201x 策略级)
 *   3xxx  参数校验
 *   4xxx  通用资源(4001 NOT_FOUND、4009 STATE_CONFLICT)
 *   41xx  Trading order 域
 *   5xxx  服务端内部错误(5001 INTERNAL_ERROR)
 *   6xxx  外部服务(交易所)
 *   70xx  Strategy 域
 *   71xx  Backtest 域
 *   72xx  Worker 编排(7200 START_FAILED)
 *   73xx  回测下单 + service token + runner 失败
 *   80xx  AI/LLM 网关
 *   90xx  Report 域
 *   10xxx MCP 域(10001 TOKEN_INVALID、10002 TOOL_PARAM_INVALID、10004 EMERGENCY_CONFIRM_REQUIRED)
 * </pre>
 */
public final class ErrorCode {
    public static final int SUCCESS = 0;
    public static final int UNAUTHENTICATED = 1001;
    public static final int FORBIDDEN = 1002;
    public static final int AUTH_RATE_LIMITED = 1003;
    public static final int VALIDATION_FAILED = 3001;
    /** 邀请码无效(不存在/已禁用/已过期/已用尽),注册门禁。 */
    public static final int INVITE_CODE_INVALID = 3002;

    public static final int RESOURCE_NOT_FOUND = 4001;
    public static final int RESOURCE_STATE_CONFLICT = 4009;
    public static final int INTERNAL_ERROR = 5001;
    public static final int EXCHANGE_UNAVAILABLE = 6001;

    // Trading 模块 41xx 段
    public static final int ORDER_ILLEGAL_STATE_TRANSITION = 4101;
    public static final int ORDER_INSUFFICIENT_BALANCE = 4102;
    public static final int ORDER_INVALID_PARAMS = 4103;
    public static final int ORDER_RISK_REJECTED = 4105;
    public static final int ORDER_MATCHING_FAILED = 4106;
    public static final int ORDER_CONCURRENCY_CONFLICT = 4107;

    // Risk 模块 20xx 段
    public static final int RISK_POLICY_NOT_FOUND = 2010;
    public static final int RISK_POLICY_CONFLICT = 2011;

    // Strategy 模块 70xx 段
    public static final int STRATEGY_NOT_FOUND = 7001;
    public static final int STRATEGY_ILLEGAL_STATE_TRANSITION = 7002;
    public static final int STRATEGY_CODE_NOT_FOUND = 7004;
    public static final int STRATEGY_CODE_ILLEGAL_STATE = 7005;
    public static final int STRATEGY_NO_PUBLISHED_CODE = 7006;
    /** 策略状态不可编辑/删除(update/delete 前置可编辑性检查;非状态机转移,与 7002 区分)。 */
    public static final int STRATEGY_NOT_EDITABLE = 7007;
    /** 官方模板 key 不存在(模板目录查询/fork),404。 */
    public static final int TEMPLATE_NOT_FOUND = 7008;

    // Backtest 71xx 段
    public static final int BACKTEST_TASK_NOT_FOUND = 7100;

    // Worker 72xx 段
    public static final int WORKER_START_FAILED = 7200;

    // 73xx 段(service token + runner 失败;7200 已被 Worker 段占用,故用 73xx)。
    // 7302/7303 空号:原账本不足/任务未运行错误随回测撮合移入 worker 而废弃。
    public static final int WORKER_TOKEN_INVALID = 7301;
    /** 回测区间无历史数据(worker 拉空 → exit 2 → markFailed)。 */
    public static final int BACKTEST_NO_MARKET_DATA = 7304;
    /** 回测 worker 环境不可用(启动自检失败:解释器/依赖缺失),提交回测前置拒绝,HTTP 503。 */
    public static final int BACKTEST_WORKER_UNAVAILABLE = 7305;
    /** 回测并发配额超限(per-user PENDING+RUNNING 达上限),HTTP 429。 */
    public static final int BACKTEST_QUOTA_EXCEEDED = 7306;
    /** worker bootstrap 拉取配置时 config registry 无此 strategyId(strategy 已停/重启竞态,token 仍有效),HTTP 404。worker 收此码 exit。 */
    public static final int WORKER_CONFIG_UNAVAILABLE = 7307;

    // AI Gateway 8xxx 段(8001 LLM_KEY_NOT_FOUND 已删——key 不存在/非本人走通用 4001/4003)
    public static final int LLM_KEY_INVALID_PROVIDER = 8002;
    public static final int LLM_PROVIDER_ERROR = 8003;
    /** 自然语言风控解析失败(LLM 输出无法提取出合法规则),400;区别于 8003 provider 错误。 */
    public static final int AI_PARSE_FAILED = 8004;

    // Report 模块 90xx 段
    public static final int REPORT_NOT_FOUND = 9001;
    public static final int REPORT_INVALID_PAYLOAD = 9002;
    public static final int REPORT_EXPORT_FAILED = 9004;

    // MCP 模块 10xxx 段
    /** PAT 无效/已吊销/已过期,filter 层 401。 */
    public static final int MCP_TOKEN_INVALID = 10001;
    /** MCP 工具入参非法(exchange/ruleType 枚举值不合法等),controller 层 400。 */
    public static final int MCP_TOOL_PARAM_INVALID = 10002;
    // 10004 已废弃(旧裸 boolean confirm,被两阶段 confirmToken 协议取代,见 10006)。保留号段不复用。
    /** PAT scope 不足(如 READ-only token 调 submit_order),403。 */
    public static final int MCP_SCOPE_DENIED = 10005;
    /** 高危写操作 confirmToken 无效(缺失走 preview 非错误;过期/已用/指纹不符/跨用户 → 此码),400。 */
    public static final int MCP_CONFIRM_TOKEN_INVALID = 10006;

    private ErrorCode() {}
}

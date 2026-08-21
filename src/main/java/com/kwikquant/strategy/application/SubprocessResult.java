package com.kwikquant.strategy.application;

/**
 * 子进程执行结果。{@link SubprocessExecutor#run} 返回。
 *
 * @param exitCode 进程退出码(超时为 -1)
 * @param stdout 标准输出(回测结果 JSON 在此)
 * @param stderr 标准错误(失败原因)
 * @param timedOut 是否超时(destroyForcibly)
 * @param stdoutTruncated stdout 是否因超过 {@link SubprocessExecutor} 上限被截断
 *     (防恶意/失控策略以超大输出耗尽 JVM 内存;截断后结果 JSON 必然解析失败 → markFailed 带明确原因)
 */
public record SubprocessResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean stdoutTruncated) {

    /** 兼容工厂:未截断的结果(绝大多数调用点)。 */
    public static SubprocessResult of(int exitCode, String stdout, String stderr, boolean timedOut) {
        return new SubprocessResult(exitCode, stdout, stderr, timedOut, false);
    }
}

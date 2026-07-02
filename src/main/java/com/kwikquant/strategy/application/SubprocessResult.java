package com.kwikquant.strategy.application;

/**
 * 子进程执行结果。{@link SubprocessExecutor#run} 返回。
 *
 * @param exitCode 进程退出码(超时为 -1)
 * @param stdout 标准输出(§8 JSON 在此)
 * @param stderr 标准错误(失败原因)
 * @param timedOut 是否超时(destroyForcibly)
 */
public record SubprocessResult(int exitCode, String stdout, String stderr, boolean timedOut) {}

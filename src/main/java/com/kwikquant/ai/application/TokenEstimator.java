package com.kwikquant.ai.application;

import java.util.List;

/**
 * Token 估算器 SPI。用于上下文窗口预算判定（不要求精确，{@link CjkTokenEstimator} 为保守高估实现，
 * 利于预算安全：宁可早压缩/多截断，不可让历史溢出 provider 窗口致 400）。
 */
public interface TokenEstimator {

    /** 估算单段文本的 token 数。null/空返 0。 */
    int estimate(String text);

    /** 估算消息列表的 token 数（累加每条 content，role overhead 忽略）。 */
    int estimate(List<ChatMessage> messages);
}

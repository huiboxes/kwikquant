package com.kwikquant.ai.application;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CjkTokenEstimator} 比例验证：CJK 1.0 token/字符，其余 0.25 token/字符（向上取整，保守高估）。
 */
class CjkTokenEstimatorTest {

    private final CjkTokenEstimator estimator = new CjkTokenEstimator();

    @Test
    void estimate_cjkText_countsOneTokenPerChar() {
        // "你好世界啊" 5 个 CJK 统一表意字符 → 5 token
        assertEquals(5, estimator.estimate("你好世界啊"));
    }

    @Test
    void estimate_englishText_countsQuarterTokenPerChar() {
        // "hello world" 11 个 ASCII 字符 × 0.25 = 2.75 → 向上取整 3 token
        assertEquals(3, estimator.estimate("hello world"));
    }

    @Test
    void estimate_cjkVersusEnglish_showsHigherDensity() {
        // 同等字符数下 CJK token 密度 4 倍于英文（保守高估，利于预算安全）
        String cjk = "策略对话历史压缩"; // 8 CJK → 8 token
        String eng = "abcdefgh"; // 8 ASCII → ceil(8*0.25)=2 token
        assertEquals(8, estimator.estimate(cjk));
        assertEquals(2, estimator.estimate(eng));
        assertTrue(estimator.estimate(cjk) > estimator.estimate(eng));
    }

    @Test
    void estimate_mixedCjkAndAscii_sumsPerCharRates() {
        // "你好abc" = 2 CJK(2.0) + 3 ASCII(0.75) = 2.75 → 向上取整 3
        assertEquals(3, estimator.estimate("你好abc"));
    }

    @Test
    void estimate_nullOrEmpty_returnsZero() {
        assertEquals(0, estimator.estimate((String) null));
        assertEquals(0, estimator.estimate(""));
    }

    @Test
    void estimate_messages_accumulatesContent() {
        // 两条消息：5 CJK + 3 token(英文 "hello world") = 8
        List<ChatMessage> msgs = List.of(new ChatMessage("user", "你好世界啊"), new ChatMessage("assistant", "hello world"));
        assertEquals(8, estimator.estimate(msgs));
    }

    @Test
    void estimate_messages_nullContentSkipped() {
        // null content 不计（防御：record 构造不触发 @NotBlank，此处验证不 NPE 且不计入）
        List<ChatMessage> msgs = List.of(new ChatMessage("user", null), new ChatMessage("assistant", "hello"));
        // "hello" 5 ASCII × 0.25 = 1.25 → 向上取整 2
        assertEquals(2, estimator.estimate(msgs));
    }

    @Test
    void estimate_emptyOrNullMessages_returnsZero() {
        assertEquals(0, estimator.estimate(List.of()));
        assertEquals(0, estimator.estimate((List<ChatMessage>) null));
    }

    @Test
    void estimate_fullwidthPunctuation_countsAsCjk() {
        // 全角标点（HALFWIDTH_AND_FULLWIDTH_FORMS 块）算 CJK（1.0 token/字符）
        String fullwidth = "：，。！"; // 4 个全角标点 → 4 token
        assertEquals(4, estimator.estimate(fullwidth));
    }
}

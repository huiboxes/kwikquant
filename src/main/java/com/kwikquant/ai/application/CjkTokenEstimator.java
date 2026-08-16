package com.kwikquant.ai.application;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CJK 感知 token 估算器：CJK 字符（中日韩统一表意文字 / 符号标点 / 平假名 / 片假名 / 谚文音节 / 全角形式）
 * 算 1.0 token/字符，其余算 0.25 token/字符（即 4 char/token，英文粗估）。
 *
 * <p>保守高估：结果向上取整，使预算判定偏紧（更易触发压缩/截断），避免历史溢出 provider 窗口。
 * 用 {@link Character.UnicodeBlock#of(char)} 判 CJK，覆盖策略对话常见中日韩 + 全角标点。
 */
@Component
public class CjkTokenEstimator implements TokenEstimator {

    private static final double CJK_TOKEN_PER_CHAR = 1.0;
    private static final double OTHER_TOKEN_PER_CHAR = 0.25;

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            tokens += isCjk(text.charAt(i)) ? CJK_TOKEN_PER_CHAR : OTHER_TOKEN_PER_CHAR;
        }
        return (int) Math.ceil(tokens);
    }

    @Override
    public int estimate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (ChatMessage m : messages) {
            if (m.content() == null) {
                continue;
            }
            for (int i = 0; i < m.content().length(); i++) {
                tokens += isCjk(m.content().charAt(i)) ? CJK_TOKEN_PER_CHAR : OTHER_TOKEN_PER_CHAR;
            }
        }
        return (int) Math.ceil(tokens);
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}

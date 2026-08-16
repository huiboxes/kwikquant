package com.kwikquant.ai.application;

import static org.junit.jupiter.api.Assertions.*;

import com.kwikquant.shared.types.LlmProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@link ContextWindowManager} 算法 10 步覆盖。用真实 {@link CjkTokenEstimator} + 自建
 * {@link ContextWindowProperties}（defaultTokens=13000 → budget=8904），summarizer 用 lambda 返固定串/抛异常。
 */
class ContextWindowManagerTest {

    private static final int WINDOW = 13_000; // budget = 13000 - 4096 = 8904（≥ MIN_BUDGET，不触发兜底）

    private final TokenEstimator estimator = new CjkTokenEstimator();
    private final ContextWindowManager mgr =
            new ContextWindowManager(estimator, new ContextWindowProperties(WINDOW, Map.of()));

    /** 50000 ASCII → 12500 token（> budget 8904，单条即可触发压缩）。 */
    private static final String BIG = "a".repeat(50_000);

    private static List<ChatMessage> history(List<ChatMessage> tail) {
        // system 在 [0]，压缩全程不动它（验证 system 保留）
        List<ChatMessage> all = new ArrayList<>();
        all.add(new ChatMessage("system", "sys"));
        all.addAll(tail);
        return all;
    }

    // 1. 估算 ≤ 预算 → 不压缩，summary=null（返回原列表同引用）。
    @Test
    void compress_underBudget_returnsUnchangedNoSummary() {
        List<ChatMessage> msgs = history(List.of(new ChatMessage("user", "hi"), new ChatMessage("assistant", "hello")));
        var cr = mgr.compress(msgs, LlmProvider.OPENAI, null, any -> "should-not-be-called");
        assertSame(msgs, cr.messages(), "未超预算应原样返回同一列表");
        assertNull(cr.summary(), "未压缩 summary 应为 null");
    }

    // 2. 超 budget → 压缩，summary 非空，返回含 system + summary-assistant + 最近 6。
    @Test
    void compress_overBudget_summarizesAndKeepsRecentSix() {
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 8; i++) { // 8 条待摘要（每条 12500 token，共 100000 > 8904 触发）
            tail.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", BIG));
        }
        for (int i = 0; i < 6; i++) { // 最近 6 条（短，保证压缩后不超预算→不触发硬闸）
            tail.add(new ChatMessage("user", "recent-" + i));
        }
        List<ChatMessage> msgs = history(tail);

        var cr = mgr.compress(msgs, LlmProvider.OPENAI, null, any -> "summary");

        assertEquals("summary", cr.summary(), "超预算压缩后 summary 非空");
        List<ChatMessage> out = cr.messages();
        assertEquals(8, out.size(), "system + summary-assistant + 最近 6");
        assertEquals("system", out.get(0).role(), "system 保留在 [0]"); // 8. system 保留在 [0]
        assertEquals("assistant", out.get(1).role());
        assertTrue(out.get(1).content().contains("（对话摘要）summary"), "摘要注入为合成 assistant 消息");
        assertEquals("recent-0", out.get(2).content(), "最近 6 条从 oldest recent 起");
        assertEquals("recent-5", out.get(7).content(), "末条为最近一条");
    }

    // 3. summarizer 抛异常 → 兜底截最旧（保留 system + 最近 6），summary=null，不崩。
    @Test
    void compress_summarizerThrows_fallsBackToTruncateNoSummary() {
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tail.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", BIG));
        }
        for (int i = 0; i < 6; i++) {
            tail.add(new ChatMessage("user", "recent-" + i));
        }
        List<ChatMessage> msgs = history(tail);

        var cr = mgr.compress(msgs, LlmProvider.OPENAI, null, failingSummarizer());

        assertNull(cr.summary(), "摘要失败 summary=null（不落库）");
        List<ChatMessage> out = cr.messages();
        assertEquals(7, out.size(), "兜底：system + 最近 6（无摘要 assistant）");
        assertEquals("system", out.get(0).role());
        assertEquals("recent-0", out.get(1).content());
        assertEquals("recent-5", out.get(6).content());
        assertTrue(out.stream().noneMatch(m -> m.content().contains("摘要")), "兜底不应注入摘要文本");
    }

    // 4. 太少（compressEnd ≤ systemIdx）→ 不压缩（即使单条超预算）。
    @Test
    void compress_tooFewMessages_doesNotCompress() {
        // system + 6 条超长（每条 12500 token，共 75000 > 8904 触发 step3，但 compressEnd=1 ≤ systemIdx=1）
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tail.add(new ChatMessage("user", BIG));
        }
        List<ChatMessage> msgs = history(tail);

        var cr = mgr.compress(msgs, LlmProvider.OPENAI, null, any -> "should-not-be-called");

        assertSame(msgs, cr.messages(), "条数太少不压缩，原样返回");
        assertNull(cr.summary());
    }

    // 5. Anthropic 归整：连续两 user 拼接；assistant 起首 → 前插 user 占位；严格交替。
    @Test
    void compress_anthropic_mergesConsecutiveRolesAndPrependsUserPlaceholder() {
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 8; i++) { // 触发压缩的待摘要历史
            tail.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", BIG));
        }
        // 最近 6：u,u,a,u,a,u（含连续两 user）
        tail.add(new ChatMessage("user", "r1"));
        tail.add(new ChatMessage("user", "r2"));
        tail.add(new ChatMessage("assistant", "r3"));
        tail.add(new ChatMessage("user", "r4"));
        tail.add(new ChatMessage("assistant", "r5"));
        tail.add(new ChatMessage("user", "r6"));
        List<ChatMessage> msgs = history(tail);

        var cr = mgr.compress(msgs, LlmProvider.ANTHROPIC, null, any -> "summary");

        assertEquals("summary", cr.summary());
        List<ChatMessage> out = cr.messages();
        assertEquals("system", out.get(0).role());
        assertEquals("user", out.get(1).role(), "首条非 system 为 assistant → 前插 user 占位");
        assertEquals("(continue)", out.get(1).content());
        assertEquals("assistant", out.get(2).role());
        assertTrue(out.get(2).content().contains("（对话摘要）summary"));
        assertEquals("user", out.get(3).role(), "连续两 user 归整为一条");
        assertEquals("r1\n\nr2", out.get(3).content(), "连续同 role 用 \\n\\n 拼接");
        // system 之后严格交替 user/assistant
        for (int i = 1; i < out.size(); i++) {
            String expected = (i % 2 == 1) ? "user" : "assistant";
            assertEquals(expected, out.get(i).role(), "位置 " + i + " 应严格交替");
        }
    }

    // 6. OPENAI 不归整（连续 user 保持两条，不前插占位）。
    @Test
    void compress_openai_doesNotNormalize() {
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tail.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", BIG));
        }
        tail.add(new ChatMessage("user", "r1"));
        tail.add(new ChatMessage("user", "r2")); // 连续两 user
        tail.add(new ChatMessage("assistant", "r3"));
        tail.add(new ChatMessage("user", "r4"));
        tail.add(new ChatMessage("assistant", "r5"));
        tail.add(new ChatMessage("user", "r6"));
        List<ChatMessage> msgs = history(tail);

        var cr = mgr.compress(msgs, LlmProvider.OPENAI, null, any -> "summary");

        assertEquals("summary", cr.summary());
        List<ChatMessage> out = cr.messages();
        assertEquals("system", out.get(0).role());
        assertEquals("assistant", out.get(1).role()); // summary-assistant
        assertEquals("user", out.get(2).role());
        assertEquals("r1", out.get(2).content());
        assertEquals("user", out.get(3).role(), "OPENAI 不归整：连续两 user 保持两条");
        assertEquals("r2", out.get(3).content(), "不拼接");
        assertTrue(out.stream().noneMatch(m -> "(continue)".equals(m.content())), "OPENAI 不前插占位");
    }

    // 7. 硬闸：compressed 仍超预算 → 从最旧非 system 丢到 ≤ budget（保 system + 末条）。
    @Test
    void compress_stillOverBudgetAfterSummary_hardGateStripsToSystemAndLast() {
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 8; i++) { // 触发压缩
            tail.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", BIG));
        }
        for (int i = 0; i < 6; i++) { // 最近 6 也超长 → 压缩后仍超预算 → 硬闸剥离
            tail.add(new ChatMessage("user", BIG + (i == 5 ? "TAIL" : "")));
        }
        List<ChatMessage> msgs = history(tail);

        var cr = mgr.compress(msgs, LlmProvider.OPENAI, null, any -> "summary");

        assertEquals("summary", cr.summary(), "压缩成功 summary 非空（即便硬闸剥离了摘要消息，落库标记仍置位）");
        List<ChatMessage> out = cr.messages();
        assertEquals(2, out.size(), "硬闸剥离到只剩 system + 末条");
        assertEquals("system", out.get(0).role());
        assertTrue(out.get(1).content().endsWith("TAIL"), "末条保留为最近一条（最末 recent）");
    }

    // 额外：by-model 窗口解析。model 命中 byModel → 大窗口 → 同样历史不压缩。
    @Test
    void compress_modelMatchedToBigWindow_doesNotCompress() {
        // gpt-4o 命中 byModel → window=128000 → budget=123904；8×12500=100000 ≤ 123904 → 不压缩
        ContextWindowManager bigWinMgr =
                new ContextWindowManager(estimator, new ContextWindowProperties(WINDOW, Map.of("gpt-4o", 128_000)));
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tail.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", BIG));
        }
        List<ChatMessage> msgs = history(tail);

        var cr = bigWinMgr.compress(msgs, LlmProvider.OPENAI, "gpt-4o-mini", any -> "should-not-be-called");

        assertSame(msgs, cr.messages(), "大窗口下同样历史不压缩");
        assertNull(cr.summary());
    }

    // 额外：budget 下限兜底（defaultTokens 过小致 budget<8192 → 取 8192）。
    @Test
    void compress_tinyWindow_floorsBudgetToMinAndStillWorks() {
        ContextWindowManager tinyMgr =
                new ContextWindowManager(estimator, new ContextWindowProperties(1_000, Map.of()));
        // budget = 1000-4096 = -3096 < 8192 → 兜底 8192。若无兜底，budget<0 会使硬闸把摘要也剥离；
        // 兜底后 compressed(summary+6 短 recent) ≤ 8192 → 不剥离 → summary 存活，验证兜底生效。
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 8; i++) { // 8 条待摘要（100000 > 8192 触发）
            tail.add(new ChatMessage("user", BIG));
        }
        for (int i = 0; i < 6; i++) { // 最近 6 条短 → compressed 不超预算 → 不触发硬闸
            tail.add(new ChatMessage("user", "r" + i));
        }
        List<ChatMessage> msgs = history(tail);

        var cr = tinyMgr.compress(msgs, LlmProvider.OPENAI, null, any -> "summary");

        assertEquals("summary", cr.summary(), "预算兜底后仍可正常压缩");
        List<ChatMessage> out = cr.messages();
        assertEquals("system", out.get(0).role());
        assertTrue(out.get(1).content().contains("（对话摘要）summary"), "兜底使 budget 正常 → 摘要存活");
    }

    private static Function<List<ChatMessage>, String> failingSummarizer() {
        return any -> {
            throw new RuntimeException("summary provider error");
        };
    }

    private static Function<List<ChatMessage>, String> blankSummarizer() {
        return any -> "  "; // blank → 触发 empty summary fallback (覆盖 L91-92)
    }

    // 8. summarizer 返回空/空白 → 兜底截最旧（同抛异常路径），summary=null。
    @Test
    void compress_summarizerReturnsBlank_fallsBackToTruncateNoSummary() {
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tail.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", BIG));
        }
        for (int i = 0; i < 6; i++) {
            tail.add(new ChatMessage("user", "recent-" + i));
        }
        List<ChatMessage> msgs = history(tail);

        var cr = mgr.compress(msgs, LlmProvider.OPENAI, null, blankSummarizer());

        assertNull(cr.summary(), "空白摘要 summary=null（不落库）");
        List<ChatMessage> out = cr.messages();
        assertEquals(7, out.size(), "兜底：system + 最近 6（无摘要 assistant）");
        assertEquals("system", out.get(0).role());
    }

    // 9. null/blank content 消息在待摘要历史中 → truncateByChars len=0(L135-136/142) + normalizeForAnthropic 跳过(L181)。
    @Test
    void compress_nullAndBlankContentMessages_skippedInCharTruncationAndNormalization() {
        List<ChatMessage> tail = new ArrayList<>();
        // 待摘要历史中放 null/blank content（会经 truncateByChars → normalizeForAnthropic）
        tail.add(new ChatMessage("user", null));       // null → truncateByChars len=0 (L135-136)
        tail.add(new ChatMessage("user", "  "));       // blank → normalize skip (L181 continue)
        for (int i = 0; i < 6; i++) { // 补齐 8 条触压阈值
            tail.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", BIG));
        }
        // 最近 6 条
        for (int i = 0; i < 6; i++) {
            tail.add(new ChatMessage("user", "r" + i));
        }
        List<ChatMessage> msgs = history(tail);

        var cr = mgr.compress(msgs, LlmProvider.ANTHROPIC, null, any -> "summary");

        assertEquals("summary", cr.summary());
        List<ChatMessage> out = cr.messages();
        assertTrue(out.stream().noneMatch(m -> m.content() == null || m.content().isBlank()),
                "归整后不应含 null 或空白消息");
    }

    // 10. model 不含任何 byModel key → resolveWindow 走 default(L121: for 循环完未命中)。
    @Test
    void compress_modelNotMatched_usesDefaultWindow() {
        ContextWindowManager byModelMgr =
                new ContextWindowManager(estimator, new ContextWindowProperties(WINDOW, Map.of("gpt-5", 256_000)));
        // "claude-sonnet" 不含 "gpt-5" → resolveWindow 走 default(WINDOW=13000→budget=8904)，
        // 8×12500=100000>8904 → 触发压缩。
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tail.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", BIG));
        }
        for (int i = 0; i < 6; i++) {
            tail.add(new ChatMessage("user", "r" + i));
        }
        List<ChatMessage> msgs = history(tail);
        var cr = byModelMgr.compress(msgs, LlmProvider.OPENAI, "claude-sonnet", any -> "summary");
        assertEquals("summary", cr.summary(), "model 未命中 byModel → 走 defaultWindow 正常压缩");
    }
}

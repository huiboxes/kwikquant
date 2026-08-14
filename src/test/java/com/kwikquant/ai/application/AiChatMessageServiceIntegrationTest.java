package com.kwikquant.ai.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.ai.domain.AiChatMessage;
import com.kwikquant.ai.infrastructure.AiChatMessageMapper;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 集成测试:{@link AiChatMessageService} 真实 DB 往返 + strategy 级联删。
 *
 * <p>覆盖:
 * <ul>
 *   <li>saveMessage + loadHistory CRUD 往返(真实 SQL,防 mapper 漏字段映射致 unit test 假绿)</li>
 *   <li>loadHistory 按 created_at 升序返</li>
 *   <li>loadHistory 非 owner 抛 OwnershipViolationException</li>
 *   <li>deleteAll 清空该策略消息</li>
 *   <li>strategy 硬删 → ai_chat_messages 级联删(FK ON DELETE CASCADE)</li>
 * </ul>
 *
 * <p>ai_chat_messages.user_id 有 FK→users.id,需先 seed 真实 user(参照
 * {@code LlmApiKeyServiceIntegrationTest.seedUser})。strategy 硬删用 raw JdbcTemplate
 * ({@code StrategyCrudService.delete} 是软删,不触发 FK CASCADE)。
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class AiChatMessageServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AiChatMessageService messageService;

    @Autowired
    StrategyCrudService strategyCrudService;

    @Autowired
    AiChatMessageMapper messageMapper;

    @Autowired
    UserMapper userMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long seedUser() {
        String u = "test-" + UUID.randomUUID();
        User user = new User();
        user.setUsername(u);
        user.setEmail(u + "@example.com");
        user.setPasswordHash("$argon2id$stub");
        user.setEnabled(true);
        userMapper.insert(user);
        return user.getId();
    }

    private record Seed(long userId, long strategyId) {}

    private Seed seed() {
        long userId = seedUser();
        StrategyDefinition s = strategyCrudService.create(
                userId, "MA-" + userId, null, "BTC/USDT", "BINANCE", "SPOT", null, null, "1h", "{}");
        return new Seed(userId, s.getId());
    }

    @Test
    void saveMessage_andLoadHistory_roundTripsThroughRealDb() {
        Seed s = seed();

        messageService.saveMessage(s.strategyId(), s.userId(), "user", "帮我优化 MA", null);
        messageService.saveMessage(s.strategyId(), s.userId(), "ai", "建议...", "gpt-4o");

        List<AiChatMessage> history = messageService.loadHistory(s.strategyId(), s.userId());
        assertThat(history).hasSize(2);
        // 按 created_at 升序:user 消息先存(createdAt 早),ai 消息后存
        assertThat(history.get(0).getRole()).isEqualTo("user");
        assertThat(history.get(0).getContent()).isEqualTo("帮我优化 MA");
        assertThat(history.get(0).getModel()).isNull(); // user 消息 model 恒 null
        assertThat(history.get(1).getRole()).isEqualTo("ai");
        assertThat(history.get(1).getModel()).isEqualTo("gpt-4o");
        // 验证 DB 回填字段
        assertThat(history.get(0).getId()).isNotNull();
        assertThat(history.get(0).getCreatedAt()).isNotNull();
        assertThat(history.get(0).getUserId()).isEqualTo(s.userId());
        assertThat(history.get(0).getStrategyId()).isEqualTo(s.strategyId());
    }

    @Test
    void loadHistory_whenNotOwner_shouldThrow() {
        Seed s = seed();
        messageService.saveMessage(s.strategyId(), s.userId(), "user", "hi", null);

        long otherUserId = seedUser();
        assertThatThrownBy(() -> messageService.loadHistory(s.strategyId(), otherUserId))
                .isInstanceOf(OwnershipViolationException.class);
    }

    @Test
    void deleteAll_clearsByStrategy() {
        Seed s = seed();
        messageService.saveMessage(s.strategyId(), s.userId(), "user", "msg1", null);
        messageService.saveMessage(s.strategyId(), s.userId(), "ai", "reply1", "gpt-4o");
        assertThat(messageService.loadHistory(s.strategyId(), s.userId())).hasSize(2);

        messageService.deleteAll(s.strategyId(), s.userId());

        assertThat(messageService.loadHistory(s.strategyId(), s.userId())).isEmpty();
    }

    @Test
    void loadHistory_whenStrategyNotFound_shouldThrow() {
        long userId = seedUser();
        // 99999999 是不存在的 strategy,getOwned 抛 StrategyNotFoundException
        assertThatThrownBy(() -> messageService.loadHistory(99999999L, userId))
                .isInstanceOf(StrategyNotFoundException.class);
    }

    @Test
    void deleteAll_whenNotOwner_shouldThrow() {
        Seed s = seed();
        messageService.saveMessage(s.strategyId(), s.userId(), "user", "hi", null);
        long otherUserId = seedUser();

        assertThatThrownBy(() -> messageService.deleteAll(s.strategyId(), otherUserId))
                .isInstanceOf(OwnershipViolationException.class);
        // 非 owner 删除失败后,原消息应仍在(owner 视角)
        assertThat(messageService.loadHistory(s.strategyId(), s.userId())).hasSize(1);
    }

    /**
     * strategy 硬删 → ai_chat_messages 级联删(FK ON DELETE CASCADE)。
     * StrategyCrudService.delete 是软删(deleted=TRUE),不触发 CASCADE;用 raw SQL 硬删验证。
     */
    @Test
    void strategyHardDelete_cascadesToAiChatMessages() {
        Seed s = seed();
        messageService.saveMessage(s.strategyId(), s.userId(), "user", "msg", null);
        messageService.saveMessage(s.strategyId(), s.userId(), "ai", "reply", "gpt-4o");
        assertThat(messageService.loadHistory(s.strategyId(), s.userId())).hasSize(2);

        // raw SQL 硬删 strategy(绕过软删),触发 FK ON DELETE CASCADE
        jdbcTemplate.update("DELETE FROM strategies WHERE id = ?", s.strategyId());

        // ai_chat_messages 应被级联删(owner 视角查 → strategy 不存在 → StrategyNotFoundException)
        assertThatThrownBy(() -> messageService.loadHistory(s.strategyId(), s.userId()))
                .isInstanceOf(StrategyNotFoundException.class);
        // 直接查 mapper 验证消息行确实被 CASCADE 删了(深度防御 WHERE user_id 仍匹配,但应返空)
        assertThat(messageMapper.listByStrategy(s.strategyId(), s.userId(), 200))
                .isEmpty();
    }

    @Test
    void saveMessage_preservesOrderAcrossMultipleMessages() {
        // 验证多条消息 created_at 升序(防 DB 时序错乱致历史乱序)
        Seed s = seed();
        for (int i = 0; i < 5; i++) {
            messageService.saveMessage(s.strategyId(), s.userId(), "user", "msg-" + i, null);
        }
        List<AiChatMessage> history = messageService.loadHistory(s.strategyId(), s.userId());
        assertThat(history).hasSize(5);
        assertThat(history.get(0).getContent()).isEqualTo("msg-0");
        assertThat(history.get(4).getContent()).isEqualTo("msg-4");
    }

    /**
     * listByStrategy 超 limit 返最近 N 条(子查询 DESC LIMIT N 外层 ASC),朴素 ASC LIMIT 会返最早 N 条
     * 致超 200 条时丢最新对话。raw SQL 插 201 条(created_at 递增 +i 秒,防快速插入同 created_at 致顺序不定),
     * 验证 limit=200 返 msg-1..msg-200(最近 200,升序),最早 msg-0 被排除。
     */
    @Test
    void listByStrategy_whenOverLimit_returnsMostRecent() {
        Seed s = seed();
        for (int i = 0; i < 201; i++) {
            jdbcTemplate.update(
                    "INSERT INTO ai_chat_messages (user_id, strategy_id, role, content, created_at) "
                            + "VALUES (?, ?, 'user', ?, now() + (? || ' seconds')::interval)",
                    s.userId(),
                    s.strategyId(),
                    "msg-" + i,
                    String.valueOf(i));
        }
        List<AiChatMessage> history = messageMapper.listByStrategy(s.strategyId(), s.userId(), 200);
        assertThat(history).hasSize(200);
        // 升序:最早返的是 msg-1(被排除最早 msg-0 后的最近 200 条,最早在前)
        assertThat(history.get(0).getContent()).isEqualTo("msg-1");
        assertThat(history.get(199).getContent()).isEqualTo("msg-200");
        // msg-0(最早)被 LIMIT DESC 排除,不在最近 200 条
        assertThat(history).noneMatch(m -> "msg-0".equals(m.getContent()));
    }
}

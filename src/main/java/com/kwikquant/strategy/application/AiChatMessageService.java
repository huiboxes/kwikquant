package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.AiChatMessage;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.AiChatMessageMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 会话消息持久化服务(tech-design §5 C 层)。
 *
 * <p>ownership 校验:每个方法先 {@link StrategyCrudService#getOwned(strategyId, userId)}
 * 校验 strategyId 属当前 userId(参照 {@code LlmApiKeyService.getOwned} 模式)。strategy 不存在
 * 抛 {@code StrategyNotFoundException}(→7001/404),非 owner 抛
 * {@code OwnershipViolationException}(→403)。
 *
 * <p>mapper 层 SQL WHERE 再含 user_id(深度防御,与 {@code LlmApiKeyMapper.deleteByIdAndUser}
 * 和 {@code StrategyMapper.softDelete} 一致),即使 caller 忘记先 getOwned 也不会越权。
 *
 * <p>保存职责(tech-design §5.2):
 * <ul>
 *   <li>user 消息:{@code AiChatController} POST /ai/chat 进来时调
 *       {@link #saveMessage}(role="user", model=null)</li>
 *   <li>AI 回复:前端 onClose 时 POST /strategies/{id}/ai/messages 调
 *       {@link #saveMessage}(role="ai", model=本次用的 model)</li>
 * </ul>
 */
@Service
public class AiChatMessageService {

    /** 会话历史返回上限(tech-design §5.2 limit 200 防爆)。 */
    static final int HISTORY_LIMIT = 200;

    private final AiChatMessageMapper mapper;
    private final StrategyCrudService crudService;

    public AiChatMessageService(AiChatMessageMapper mapper, StrategyCrudService crudService) {
        this.mapper = mapper;
        this.crudService = crudService;
    }

    /**
     * 保存一条会话消息。
     *
     * @param strategyId 策略 ID(必须属当前 userId,否则抛 StrategyNotFoundException/OwnershipViolationException)
     * @param userId 当前用户 ID
     * @param role 消息角色:"user" 或 "ai"(由 caller 指定,本服务不做白名单校验,语义简单)
     * @param content 消息内容
     * @param model AI 消息溯源用的 model(可空;user 消息恒为 null)
     * @return 已持久化的实体(id/createdAt 由 DB 回填)
     */
    @Transactional
    public AiChatMessage saveMessage(long strategyId, long userId, String role, String content, String model) {
        // ownership 校验:strategyId 必须属当前 userId(参照 StrategyCrudService.getOwned)
        // getOwned 内部 OwnershipCheck.requireOwned 抛 StrategyNotFoundException(不存在/软删)
        // 或 OwnershipViolationException(非 owner),都由 StrategyExceptionHandler/GlobalExceptionHandler 映射
        StrategyDefinition s = crudService.getOwned(strategyId, userId);
        AiChatMessage m = new AiChatMessage();
        m.setUserId(s.getUserId());
        m.setStrategyId(strategyId);
        m.setRole(role);
        m.setContent(content);
        m.setModel(model);
        mapper.insert(m);
        return m;
    }

    /**
     * 查询策略会话历史(created_at 升序,limit 200 防爆)。
     *
     * <p>ownership 校验同 {@link #saveMessage}。mapper SQL WHERE 再含 user_id 深度防御。
     */
    public List<AiChatMessage> loadHistory(long strategyId, long userId) {
        crudService.getOwned(strategyId, userId);
        return mapper.listByStrategy(strategyId, userId, HISTORY_LIMIT);
    }

    /**
     * 清空策略会话消息。ownership 校验同 {@link #saveMessage}。
     */
    @Transactional
    public void deleteAll(long strategyId, long userId) {
        crudService.getOwned(strategyId, userId);
        mapper.deleteByStrategy(strategyId, userId);
    }
}

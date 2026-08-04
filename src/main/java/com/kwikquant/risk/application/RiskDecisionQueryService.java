package com.kwikquant.risk.application;

import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.infrastructure.RiskDecisionMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 风控决策查询服务(读模型)。封装 {@link RiskDecisionMapper} 的查询方法,供 controller 调用,
 * 避免 controller 直连 infrastructure mapper(违反分层:controller 应只依赖 application service)。
 *
 * <p>与 {@link RiskService}(写:evaluate/check)分离,遵循 SRP,避免 RiskService 变 God class。
 * 当前为薄封装 delegate,但恢复了分层一致性,未来可加缓存/权限等切面。
 */
@Service
public class RiskDecisionQueryService {

    private final RiskDecisionMapper decisionMapper;

    public RiskDecisionQueryService(RiskDecisionMapper decisionMapper) {
        this.decisionMapper = decisionMapper;
    }

    public RiskDecision findByOrderId(long orderId) {
        return decisionMapper.findByOrderId(orderId);
    }

    public List<RiskDecision> findByAccount(
            long accountId, String verdict, Instant startTime, Instant endTime, int limit, int offset) {
        return decisionMapper.findByAccount(accountId, verdict, startTime, endTime, limit, offset);
    }

    public long countByAccount(long accountId, String verdict, Instant startTime, Instant endTime) {
        return decisionMapper.countByAccount(accountId, verdict, startTime, endTime);
    }

    public List<RiskDecision> findByUserId(
            long userId, String verdict, Instant startTime, Instant endTime, int limit, int offset) {
        return decisionMapper.findByUserId(userId, verdict, startTime, endTime, limit, offset);
    }

    public long countByUserId(long userId, String verdict, Instant startTime, Instant endTime) {
        return decisionMapper.countByUserId(userId, verdict, startTime, endTime);
    }
}

package com.kwikquant.strategy.interfaces;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kwikquant.shared.infra.GlobalExceptionHandler;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.application.StrategyLifecycleService;
import com.kwikquant.strategy.domain.IllegalStrategyStateTransitionException;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyNotEditableException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * {@link StrategyController} MockMvc 测试(standalone,JWT 鉴权通过预设 SecurityContext 模拟,
 * 参照 {@code AiChatControllerTest} 模式)。
 *
 * <p>覆盖 POST /api/v1/strategies/{id}/restart:STOPPED→RUNNING happy path(切账户/用已绑账户)
 * + 状态不可转移 7002/409;DELETE /strategies/{id}:READY 可删(happy)+ RUNNING 不可删 7007/409。
 */
class StrategyControllerTest {

    private MockMvc mockMvc;
    private StrategyCrudService crudService;
    private StrategyLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        crudService = mock(StrategyCrudService.class);
        lifecycleService = mock(StrategyLifecycleService.class);
        var controller = new StrategyController(crudService, lifecycleService);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // SecurityUtils.currentUserId() 需认证上下文(userId=42,与 AiChatControllerTest 一致)
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("42", null, List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new StrategyExceptionHandler(), new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void restart_stoppedStrategy_returnsRunning() throws Exception {
        StrategyDefinition s = runningStrategy(128L);
        when(lifecycleService.restart(eq(128L), eq(42L), eq(7L))).thenReturn(s);

        mockMvc.perform(post("/api/v1/strategies/128/restart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(128))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void restart_nullAccountId_usesBoundAccount() throws Exception {
        // accountId 不传(body {}),后端用已绑账户;mock restart(128,42,null)
        StrategyDefinition s = runningStrategy(128L);
        when(lifecycleService.restart(eq(128L), eq(42L), isNull())).thenReturn(s);

        mockMvc.perform(post("/api/v1/strategies/128/restart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void restart_nonStopped_returns7002() throws Exception {
        when(lifecycleService.restart(eq(128L), eq(42L), eq(7L)))
                .thenThrow(new IllegalStrategyStateTransitionException(StrategyStatus.READY, StrategyStatus.RUNNING));

        mockMvc.perform(post("/api/v1/strategies/128/restart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(7002));
    }

    @Test
    void delete_ready_returns200() throws Exception {
        // READY 无活跃 worker(ready 仅 CAS 不 start worker),删除安全 —— 修复 READY->READY bug
        doNothing().when(crudService).delete(eq(128L), eq(42L));

        mockMvc.perform(delete("/api/v1/strategies/128"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void delete_running_returns7007() throws Exception {
        // RUNNING worker 活跃下单,不可直接删(需先 stop)→ 7007 STRATEGY_NOT_EDITABLE
        doThrow(new StrategyNotEditableException(StrategyStatus.RUNNING, "删除"))
                .when(crudService)
                .delete(eq(128L), eq(42L));

        mockMvc.perform(delete("/api/v1/strategies/128"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(7007));
    }

    private StrategyDefinition runningStrategy(long id) {
        StrategyDefinition s =
                StrategyDefinition.create(42L, "BTC Rider", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(id);
        s.setStatus(StrategyStatus.RUNNING);
        s.setExchangeAccountId(7L);
        return s;
    }
}

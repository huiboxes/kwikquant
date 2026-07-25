package com.kwikquant.strategy.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.kwikquant.strategy.application.BacktestTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * BacktestProgressController 单测:验证 worker 进度上报端点委托 service + 返 204。
 * (X-Worker-Token 鉴权由 WorkerTokenFilter 拦,见 WorkerTokenFilterTest;@Valid bean validation
 * 由 Spring MVC 在 controller 层触发,单元测试直接调方法测委托逻辑。)
 */
class BacktestProgressControllerTest {

    @Test
    void reportProgress_delegatesToServiceAndReturns204() {
        BacktestTaskService taskService = mock(BacktestTaskService.class);
        BacktestProgressController controller = new BacktestProgressController(taskService);

        var resp = controller.reportProgress(42L, new BacktestProgressController.BacktestProgressRequest(4400, 8760));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resp.hasBody()).isFalse();
        verify(taskService).reportProgress(42L, 4400, 8760);
        verifyNoMoreInteractions(taskService);
    }
}

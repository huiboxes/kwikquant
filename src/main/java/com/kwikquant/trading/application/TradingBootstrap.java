package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Trading 模块启动恢复。ApplicationReady 后遍历所有 ExchangeAccount，按 paperTrading 字段分别调用
 * PaperExecutor.bootstrapActivePaperOrders 或 LiveExecutor.startupSnapshot。
 *
 * <p>单个账户失败不阻断其余；失败记录日志 + 审计（Wave 5+ 接入告警）。
 */
@Component
public class TradingBootstrap {

    private static final Logger log = LoggerFactory.getLogger(TradingBootstrap.class);

    private final ExchangeAccountService accountService;
    private final PaperExecutor paperExecutor;
    private final LiveExecutor liveExecutor;

    public TradingBootstrap(
            ExchangeAccountService accountService,
            PaperExecutor paperExecutor,
            LiveExecutor liveExecutor) {
        this.accountService = accountService;
        this.paperExecutor = paperExecutor;
        this.liveExecutor = liveExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapAllAccounts() {
        log.info("[trading-bootstrap] starting account recovery...");
        List<ExchangeAccount> accounts;
        try {
            accounts = accountService.findAll();
        } catch (RuntimeException e) {
            log.error("[trading-bootstrap] failed to load accounts: {}", e.getMessage(), e);
            return;
        }

        int paperCount = 0;
        int liveCount = 0;
        int failCount = 0;

        for (ExchangeAccount account : accounts) {
            try {
                if (account.isPaperTrading()) {
                    paperExecutor.bootstrapActivePaperOrders(account.getId());
                    paperCount++;
                } else {
                    liveExecutor.startupSnapshot(account);
                    liveCount++;
                }
            } catch (RuntimeException e) {
                failCount++;
                log.error(
                        "[trading-bootstrap] account {} ({}) bootstrap failed: {}",
                        account.getId(),
                        account.isPaperTrading() ? "paper" : "live",
                        e.getMessage(),
                        e);
            }
        }

        log.info(
                "[trading-bootstrap] completed: {} paper, {} live, {} failed (total {})",
                paperCount,
                liveCount,
                failCount,
                accounts.size());
    }
}

package com.kwikquant.risk.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.risk.infrastructure.RiskDecisionMapper;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link RiskDecisionController} — verifies ownership chain (H7):
 * decision.accountId -> ExchangeAccountService.getOwned, and that non-owners receive 404
 * (ResourceNotFoundException) rather than 403 to prevent existence probing.
 */
class RiskDecisionControllerTest {

    private RiskDecisionMapper decisionMapper;
    private ExchangeAccountService accountService;
    private RiskDecisionController controller;

    @BeforeEach
    void setUp() {
        decisionMapper = mock(RiskDecisionMapper.class);
        accountService = mock(ExchangeAccountService.class);
        controller = new RiskDecisionController(decisionMapper, accountService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getDecision_whenOwner_returnsDecisionDto() {
        RiskDecision decision = new RiskDecision();
        decision.setId(1L);
        decision.setOrderId(100L);
        decision.setAccountId(7L);
        decision.setVerdict(RiskVerdict.REJECTED);
        decision.setRuleResults(List.of(new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "exceeds")));
        decision.setRequestId("req-1");
        when(decisionMapper.findByOrderId(100L)).thenReturn(decision);
        when(accountService.getOwned(7L, 42L)).thenReturn(new ExchangeAccount());

        var response = controller.getByOrderId(100L);

        assertThat(response.data().orderId()).isEqualTo(100L);
        assertThat(response.data().verdict()).isEqualTo("REJECTED");
        assertThat(response.data().ruleResults()).hasSize(1);
        assertThat(response.data().ruleResults().getFirst().ruleType()).isEqualTo("MAX_NOTIONAL");
    }

    @Test
    void getDecision_whenNotFound_throwsResourceNotFound() {
        when(decisionMapper.findByOrderId(999L)).thenReturn(null);
        assertThatThrownBy(() -> controller.getByOrderId(999L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getDecision_whenNotOwner_throwsResourceNotFoundToPreventProbing() {
        // H7: non-owner must get 404 (not 403) so they cannot distinguish "does not exist"
        // from "belongs to someone else".
        RiskDecision decision = new RiskDecision();
        decision.setOrderId(100L);
        decision.setAccountId(7L);
        when(decisionMapper.findByOrderId(100L)).thenReturn(decision);
        when(accountService.getOwned(7L, 42L)).thenThrow(new OwnershipViolationException("exchange_account"));

        assertThatThrownBy(() -> controller.getByOrderId(100L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listByAccount_whenOwner_returnsPaginatedDecisions() {
        when(accountService.getOwned(7L, 42L)).thenReturn(new ExchangeAccount());
        RiskDecision d1 = new RiskDecision();
        d1.setId(1L);
        d1.setOrderId(100L);
        d1.setAccountId(7L);
        d1.setVerdict(RiskVerdict.APPROVED);
        d1.setRuleResults(List.of());
        d1.setRequestId("req-1");
        when(decisionMapper.findByAccount(eq(7L), isNull(), isNull(), isNull(), eq(50), eq(0)))
                .thenReturn(List.of(d1));
        when(decisionMapper.countByAccount(eq(7L), isNull(), isNull(), isNull()))
                .thenReturn(1L);

        var response = controller.listByAccount(7L, null, null, null, 1, 50);

        assertThat(response.data().content()).hasSize(1);
        assertThat(response.data().total()).isEqualTo(1);
    }

    @Test
    void listByAccount_withVerdictFilter_normalizesToUppercase() {
        when(accountService.getOwned(7L, 42L)).thenReturn(new ExchangeAccount());
        when(decisionMapper.findByAccount(eq(7L), eq("REJECTED"), isNull(), isNull(), eq(50), eq(0)))
                .thenReturn(List.of());
        when(decisionMapper.countByAccount(eq(7L), eq("REJECTED"), isNull(), isNull()))
                .thenReturn(0L);

        var response = controller.listByAccount(7L, "rejected", null, null, 1, 50);

        assertThat(response.data().content()).isEmpty();
        verify(decisionMapper).findByAccount(eq(7L), eq("REJECTED"), isNull(), isNull(), eq(50), eq(0));
    }

    @Test
    void listByAccount_pageSizeClamped_toMax200() {
        when(accountService.getOwned(7L, 42L)).thenReturn(new ExchangeAccount());
        when(decisionMapper.findByAccount(eq(7L), isNull(), isNull(), isNull(), eq(200), eq(0)))
                .thenReturn(List.of());
        when(decisionMapper.countByAccount(eq(7L), isNull(), isNull(), isNull()))
                .thenReturn(0L);

        // pageSize=500 → clamped to 200
        controller.listByAccount(7L, null, null, null, 1, 500);

        verify(decisionMapper).findByAccount(eq(7L), isNull(), isNull(), isNull(), eq(200), eq(0));
    }

    @Test
    void listByAccount_withTimeRange_passesFilters() {
        when(accountService.getOwned(7L, 42L)).thenReturn(new ExchangeAccount());
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-12-31T23:59:59Z");
        when(decisionMapper.findByAccount(eq(7L), isNull(), eq(start), eq(end), eq(50), eq(0)))
                .thenReturn(List.of());
        when(decisionMapper.countByAccount(eq(7L), isNull(), eq(start), eq(end)))
                .thenReturn(0L);

        controller.listByAccount(7L, null, start, end, 1, 50);

        verify(decisionMapper).findByAccount(eq(7L), isNull(), eq(start), eq(end), eq(50), eq(0));
    }
}

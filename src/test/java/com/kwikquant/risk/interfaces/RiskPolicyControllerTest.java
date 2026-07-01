package com.kwikquant.risk.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.risk.application.RiskPolicyManagementService;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link RiskPolicyController} — verifies request parsing, DTO projection,
 * and that the current user id is propagated to the service for ownership checks (H6).
 */
class RiskPolicyControllerTest {

    private RiskPolicyManagementService managementService;
    private RiskPolicyController controller;

    @BeforeEach
    void setUp() {
        managementService = mock(RiskPolicyManagementService.class);
        controller = new RiskPolicyController(managementService);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_whenValidRequest_returnsCreatedPolicyDto() {
        RiskPolicyRequest req =
                new RiskPolicyRequest(1L, "MAX_NOTIONAL", "My Policy", Map.of("maxNotionalUsdt", "50000"));
        RiskPolicy policy = policy(10L, 1L, RiskRuleType.MAX_NOTIONAL, "My Policy", true);
        when(managementService.create(eq(1L), eq(42L), eq(RiskRuleType.MAX_NOTIONAL), eq("My Policy"), any()))
                .thenReturn(policy);

        var response = controller.create(req);

        assertThat(response.data().id()).isEqualTo(10L);
        assertThat(response.data().ruleType()).isEqualTo("MAX_NOTIONAL");
        assertThat(response.data().enabled()).isTrue();
        // H6: currentUserId (42) must be propagated for ownership check
        verify(managementService).create(eq(1L), eq(42L), eq(RiskRuleType.MAX_NOTIONAL), eq("My Policy"), any());
    }

    @Test
    void create_whenInvalidRuleType_throwsIllegalArgument() {
        RiskPolicyRequest req = new RiskPolicyRequest(1L, "BOGUS", "Bad", Map.of("maxNotionalUsdt", "50000"));
        assertThatThrownBy(() -> controller.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid rule type");
    }

    @Test
    void list_whenOwner_returnsPolicyDtos() {
        when(managementService.listByAccount(1L, 42L))
                .thenReturn(List.of(policy(10L, 1L, RiskRuleType.MAX_NOTIONAL, "P1", true)));

        var response = controller.list(1L);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().getFirst().name()).isEqualTo("P1");
        verify(managementService).listByAccount(1L, 42L);
    }

    @Test
    void toggle_whenValidRequest_returnsToggledPolicy() {
        RiskPolicy policy = policy(10L, 1L, RiskRuleType.MAX_NOTIONAL, "P", false);
        when(managementService.toggle(10L, 42L, false)).thenReturn(policy);

        var response = controller.toggle(10L, new ToggleRequest(false));

        assertThat(response.data().enabled()).isFalse();
        verify(managementService).toggle(10L, 42L, false);
    }

    @Test
    void update_whenValidRequest_returnsUpdatedPolicyDto() {
        RiskPolicyRequest req =
                new RiskPolicyRequest(1L, "MAX_NOTIONAL", "Updated Name", Map.of("maxNotionalUsdt", "20000"));
        RiskPolicy policy = policy(10L, 1L, RiskRuleType.MAX_NOTIONAL, "Updated Name", true);
        policy.setParams(Map.of("maxNotionalUsdt", "20000"));
        when(managementService.update(eq(10L), eq(42L), eq("Updated Name"), any()))
                .thenReturn(policy);

        var response = controller.update(10L, req);

        assertThat(response.data().id()).isEqualTo(10L);
        assertThat(response.data().name()).isEqualTo("Updated Name");
        assertThat(response.data().params()).containsEntry("maxNotionalUsdt", "20000");
        // H6: currentUserId (42) must be propagated for ownership check
        verify(managementService).update(eq(10L), eq(42L), eq("Updated Name"), any());
    }

    @Test
    void delete_delegatesToServiceWithCurrentUserId() {
        controller.delete(10L);
        verify(managementService).delete(10L, 42L);
    }

    private RiskPolicy policy(long id, long accountId, RiskRuleType type, String name, boolean enabled) {
        RiskPolicy p = new RiskPolicy();
        p.setId(id);
        p.setAccountId(accountId);
        p.setRuleType(type);
        p.setName(name);
        p.setEnabled(enabled);
        return p;
    }
}

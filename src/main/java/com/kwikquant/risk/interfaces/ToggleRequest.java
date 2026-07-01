package com.kwikquant.risk.interfaces;

/**
 * Request DTO for toggling a risk policy's enabled state.
 *
 * @param enabled whether the policy should be enabled or disabled
 */
public record ToggleRequest(boolean enabled) {}

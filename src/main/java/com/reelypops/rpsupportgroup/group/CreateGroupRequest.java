package com.reelypops.rpsupportgroup.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Idempotent SG intake payload (P1): the group's Instagram account (the SG-key) plus an <em>optional</em>
 * {@link GroupDefinition} + group {@code description}. A name-only request omits the definition (the admin/AI fills it
 * during vetting); a full "create your own" upload supplies it.
 */
public record CreateGroupRequest(
        @NotBlank String igAccount,
        @Valid GroupDefinition definition,
        String description) {
}

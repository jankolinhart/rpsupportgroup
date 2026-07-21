package com.reelypops.rpsupportgroup.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Payload to register a new (unclaimed) SG config: the group's Instagram account (the SG-key) plus its
 * authoritative {@link GroupDefinition} and an optional group {@code description} (Cycle 11, R-1).
 */
public record CreateGroupRequest(
        @NotBlank String igAccount,
        @NotNull @Valid GroupDefinition definition,
        String description) {
}

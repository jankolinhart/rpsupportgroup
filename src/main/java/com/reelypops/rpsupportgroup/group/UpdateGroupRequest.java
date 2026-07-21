package com.reelypops.rpsupportgroup.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Operator-correction payload (Cycle 11, R-1): the authoritative {@link GroupDefinition} plus the optional group
 * {@code description}. Extends the earlier definition-only correction so an admin can fix timings/timezone/opening
 * weekdays <em>and</em> the description in one call.
 */
public record UpdateGroupRequest(
        @NotNull @Valid GroupDefinition definition,
        String description) {
}

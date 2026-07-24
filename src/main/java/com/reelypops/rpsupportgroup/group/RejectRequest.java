package com.reelypops.rpsupportgroup.group;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Operator soft-reject payload (P1): the {@code reason} shown to the requester + the {@code cooldownDays} before the
 * group may be re-requested.
 */
public record RejectRequest(@NotBlank String reason, @Min(0) int cooldownDays) {
}

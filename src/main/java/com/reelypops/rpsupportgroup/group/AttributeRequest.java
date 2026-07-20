package com.reelypops.rpsupportgroup.group;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Admin manual-attribution payload (Cycle 9): the ReelyPops user id to make the owner of an unclaimed config,
 * WITHOUT the normal claim (verify + subscribe) flow.
 */
public record AttributeRequest(@NotNull UUID ownerId) {
}

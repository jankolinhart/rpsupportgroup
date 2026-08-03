package com.reelypops.rpsupportgroup.group;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One client-reported drift observation forwarded by rpenduser (M5 re-vet consumer). A {@link DriftKind#MARKER_DISAGREE}
 * carries the reporter's latest agree/disagree tally + client-side persistence count; a {@link DriftKind#NEW_OWNER}
 * carries the nominated candidate owner's handle. Upserted per reporter on the receiving config.
 */
public record DriftReport(
        @NotNull DriftKind kind,
        @NotBlank String reporterDeviceId,
        UUID reporterUserId,
        String nominatedOwnerHandle,
        Integer agreePass,
        Integer disagreePass,
        Integer persistenceCount) {
}

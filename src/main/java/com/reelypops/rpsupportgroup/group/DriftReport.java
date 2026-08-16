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
        Integer persistenceCount,
        String markerRole,
        Integer imageDistance,
        Integer imageThreshold,
        String evidencePostId,
        /**
         * The picture the marker was ACTUALLY posted with, captured by the client and sent as base64.
         *
         * <p>Directive B1: no cloud service ever contacts Instagram, so this is the only route by which an
         * administrator can see what the owner is posting today — and therefore the only way to re-vet a drifted
         * banner without a full duty scrape. Optional: the measurement still stands without it, the administrator
         * simply has nothing to look at.</p>
         */
        byte[] evidenceImage) {
}

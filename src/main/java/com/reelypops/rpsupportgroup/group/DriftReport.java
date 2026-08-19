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
        /**
         * The OCR text of the reference the measurement was taken against — which reference, not just which role.
         *
         * <p>Load-bearing for adoption: a per-weekday group carries several banners for one role
         * (`glowbloggeragency` has a generic weekday START and a distinct "START Sonntag"), so role alone would
         * spread Sunday's picture across every weekday's START reference.</p>
         */
        String markerText,
        /** For {@link DriftKind#MARKER_REFERENCE_CORRUPT}: the malformed value and why it is malformed. */
        String detail,
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
        byte[] evidenceImage,
        /**
         * THE CLIENT'S OWN fingerprint of {@code evidenceImage} — the only hash this service may store.
         *
         * <p>Our {@code ImageDHash} lands 15–34 bits away for identical bytes (measured 16/08/2026), and clients
         * match at 4–10. A hash we computed would look healthy and never match. Store this verbatim.</p>
         */
        String evidenceImageHash,
        /**
         * WHICH weekday slot (JS 0=Sun … 6=Sat) the drifting reference belongs to — resolved by the CLIENT from
         * the marker's own postedOn through the schedule's day-offsets; {@code null} for a flat/legacy group or
         * an old client. Adoption writes into exactly this day's slot: without it the only possible write was
         * "every day sharing the role+text", which stamped all seven START slots with one day's banner
         * (18/08/2026).
         */
        Integer markerWeekday) {
}

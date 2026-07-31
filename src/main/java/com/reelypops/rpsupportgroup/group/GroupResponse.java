package com.reelypops.rpsupportgroup.group;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An SG config as returned by the API — the shared {@link GroupDefinition} + lifecycle + the {@code version}
 * ETag the client polls (Q1) + the admin-curated content-category slugs (Cycle 12). Both the JWT client surface
 * and the internal scanner/BFF surface return this.
 *
 * <p><strong>M5:</strong> also carries the confirmed per-weekday {@link WeeklyScheduleDefinition weeklySchedule} (the
 * authoritative program the client now consumes directly — plan P5), taken from the vetted profile; {@code null} for a
 * config with no vetted profile yet, or a legacy flat-only vetted profile. The flat {@link #definition} remains the
 * one-way projection during the transition, so an older client keeps working until it repoints to {@code weeklySchedule}.
 */
public record GroupResponse(
        UUID id,
        String igAccount,
        ConfigStatus status,
        UUID ownerId,
        boolean adminAttributed,
        boolean vetted,
        VettingState vettingState,
        String rejectReason,
        Instant cooldownUntil,
        Instant rejectedAt,
        GroupDefinition definition,
        WeeklyScheduleDefinition weeklySchedule,
        String description,
        long version,
        SgConfigMode mode,
        Long activeSnapshotVersion,
        Instant createdAt,
        Instant updatedAt,
        List<String> categories) {

    static GroupResponse of(SupportGroupConfig c) {
        return new GroupResponse(c.getId(), c.getIgAccount(), c.getStatus(), c.getOwnerId(),
                c.isAdminAttributed(), c.isVetted(), c.getVettingState(), c.getRejectReason(), c.getCooldownUntil(),
                c.getRejectedAt(), c.getDefinition(), weeklyScheduleOf(c), c.getDescription(), c.getVersion(),
                c.getMode(), c.getActiveSnapshotVersion(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getCategories().stream().map(SgCategory::getSlug).sorted().toList());
    }

    /** The vetted profile's per-weekday schedule (M5, plan P5), or {@code null} when there is no vetted profile. */
    private static WeeklyScheduleDefinition weeklyScheduleOf(SupportGroupConfig c) {
        return c.getVettedProfile() == null ? null : c.getVettedProfile().weeklySchedule();
    }
}

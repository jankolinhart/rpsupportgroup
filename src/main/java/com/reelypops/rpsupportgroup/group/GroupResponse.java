package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.group.VettedProfileVersion.ChangeNoteEntry;

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
        List<ChangeNoteEntry> changeNote,
        boolean needsRevet,
        List<RevetReason> revetReasons,
        Instant createdAt,
        Instant updatedAt,
        List<String> categories) {

    static GroupResponse of(SupportGroupConfig c) {
        return of(c, List.of());
    }

    /**
     * M5.3b: the client GET carries the active snapshot's structured field-level change-note so the desktop client
     * can render a {@code CONFIG_CHANGED} announcement on adopt. Mutation / admin-list responses use
     * {@link #of(SupportGroupConfig)} (empty note) — the note only matters when the client polls the current config.
     */
    static GroupResponse of(SupportGroupConfig c, List<ChangeNoteEntry> changeNote) {
        return of(c, changeNote, false, List.of());
    }

    /**
     * M5 re-vet consumer: the admin surfaces also carry the config's derived {@code needsRevet} flag + a
     * {@link RevetReason} per open drift kind (why), computed from unresolved marker-disagree + new-owner drift
     * observations. It is a pure read-side signal — the config itself is never mutated — so mutation responses leave it
     * {@code false}/empty.
     */
    static GroupResponse of(SupportGroupConfig c, List<ChangeNoteEntry> changeNote, boolean needsRevet,
                            List<RevetReason> revetReasons) {
        return new GroupResponse(c.getId(), c.getIgAccount(), c.getStatus(), c.getOwnerId(),
                c.isAdminAttributed(), c.isVetted(), c.getVettingState(), c.getRejectReason(), c.getCooldownUntil(),
                c.getRejectedAt(), c.getDefinition(), weeklyScheduleOf(c), c.getDescription(), c.getVersion(),
                c.getMode(), c.getActiveSnapshotVersion(), changeNote, needsRevet, revetReasons,
                c.getCreatedAt(), c.getUpdatedAt(),
                c.getCategories().stream().map(SgCategory::getSlug).sorted().toList());
    }

    /** The vetted profile's per-weekday schedule (M5, plan P5), or {@code null} when there is no vetted profile. */
    private static WeeklyScheduleDefinition weeklyScheduleOf(SupportGroupConfig c) {
        return c.getVettedProfile() == null ? null : c.getVettedProfile().weeklySchedule();
    }
}

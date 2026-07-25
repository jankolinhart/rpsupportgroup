package com.reelypops.rpsupportgroup.group;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The authoritative SG <em>definition</em> (Q2) — the group's shared truth that every ReelyPops client and the
 * scanner read <em>identically</em> to agree on round detection. Mirrors the client's config model
 * (time-of-day + day-offset, evaluated in the group's {@code timezone} — Cycle 9), stored as jsonb on
 * {@link SupportGroupConfig} so it can evolve without a migration. User-local <em>preferences</em> (auto-like on/off,
 * liking cadence, notification prefs) live on the client and are never held here.
 *
 * @param type                       round-boundary type (§3)
 * @param timezone                   IANA zone all times below are evaluated in
 * @param markerOwners               IG handles whose marker posts delimit rounds (§2, §5b); grows via discovery (Q4)
 * @param maxTaggedPosts             max tagged posts a member may hold in a round, if the group caps it
 * @param continuousDays             weekday indices the group is open (0=Sun … 6=Sat), for CONTINUOUS groups
 * @param startMarkerTime            time-of-day of the start marker (2-marker groups), {@code HH:mm}
 * @param endMarkerTime              time-of-day of the end marker (2-marker groups), {@code HH:mm}
 * @param endMarkerDayOffset         day offset of the end marker relative to the start
 * @param singleMarkerTime           time-of-day of the single marker (single-marker groups), {@code HH:mm}
 * @param likesUntilTime             time-of-day by which liking duty must be complete, {@code HH:mm}
 * @param likesUntilDayOffset        day offset of the liking deadline
 * @param tagRemoveEarliestTime      earliest safe tag-removal time-of-day, {@code HH:mm}
 * @param tagRemoveEarliestDayOffset day offset of the safe tag-removal time
 * @param openWeekdays               weekday indices the group is <em>open</em> (0=Sun … 6=Sat) — the general
 *                                   opening-days field for <em>all</em> SG types (Cycle 11, R-1). Distinct from the
 *                                   liking rounds: opening times are a semantic group-config item that cannot be
 *                                   derived and are set at creation. For CONTINUOUS groups it also drives the
 *                                   round-reset day set, so {@link #canonicalized()} keeps {@code continuousDays} in
 *                                   sync with it.
 */
public record GroupDefinition(
        @NotNull SgType type,
        @NotBlank String timezone,
        List<String> markerOwners,
        Integer maxTaggedPosts,
        List<Integer> continuousDays,
        String startMarkerTime,
        String endMarkerTime,
        Integer endMarkerDayOffset,
        String singleMarkerTime,
        String likesUntilTime,
        Integer likesUntilDayOffset,
        String tagRemoveEarliestTime,
        Integer tagRemoveEarliestDayOffset,
        List<Integer> openWeekdays) {

    /** Return a copy with a replaced marker-owner list (records are immutable). */
    public GroupDefinition withMarkerOwners(List<String> owners) {
        return new GroupDefinition(type, timezone, owners, maxTaggedPosts, continuousDays, startMarkerTime,
                endMarkerTime, endMarkerDayOffset, singleMarkerTime, likesUntilTime, likesUntilDayOffset,
                tagRemoveEarliestTime, tagRemoveEarliestDayOffset, openWeekdays);
    }

    /**
     * Return a write-normalised copy (Cycle 11, R-1). {@code openWeekdays} is the canonical opening-days field for
     * every type; for a CONTINUOUS group the client's round-reset still reads {@code continuousDays}, so the two are
     * kept in sync — an explicit {@code openWeekdays} wins, else it is back-filled from a legacy {@code continuousDays}.
     * Non-continuous groups keep {@code openWeekdays} standalone (their {@code continuousDays} is irrelevant to rounds).
     */
    public GroupDefinition canonicalized() {
        if (type == SgType.CONTINUOUS) {
            List<Integer> days = (openWeekdays != null && !openWeekdays.isEmpty()) ? openWeekdays : continuousDays;
            return new GroupDefinition(type, timezone, markerOwners, maxTaggedPosts, days, startMarkerTime,
                    endMarkerTime, endMarkerDayOffset, singleMarkerTime, likesUntilTime, likesUntilDayOffset,
                    tagRemoveEarliestTime, tagRemoveEarliestDayOffset, days);
        }
        if (type == SgType.SINGLE_MARKER) {
            // Derive the day-offsets from the times (the operator only sets times): the liking-end sits at the first
            // likes-until time AT/AFTER the NEXT marker (a grace period → day 1, or day 2 when it crosses midnight
            // past the marker), and safe-removal is STRICTLY AFTER the liking-end. Keeps the client timeline in the
            // correct order (marker → liking-end → safe-removal) and consistent with the scheduler.
            Integer likesOff = deriveLikesUntilDayOffset(singleMarkerTime, likesUntilTime, likesUntilDayOffset);
            Integer removeOff = deriveTagRemoveDayOffset(likesUntilTime, likesOff, tagRemoveEarliestTime, tagRemoveEarliestDayOffset);
            return new GroupDefinition(type, timezone, markerOwners, maxTaggedPosts, continuousDays, startMarkerTime,
                    endMarkerTime, endMarkerDayOffset, singleMarkerTime, likesUntilTime, likesOff,
                    tagRemoveEarliestTime, removeOff, openWeekdays);
        }
        return this;
    }

    /** Minutes-of-day for an {@code HH:mm} string, or {@code null} when absent/unparseable. */
    private static Integer minutesOfDay(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        String[] parts = hhmm.trim().split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Single-marker liking-end day offset: the first likes-until time at/after the NEXT marker (grace) → 1 day
     *  after the opening marker, or 2 when the time falls before the marker time (crossed midnight). Falls back to
     *  the explicit value when either time is unparseable. */
    private static Integer deriveLikesUntilDayOffset(String marker, String likesUntil, Integer explicit) {
        Integer m = minutesOfDay(marker);
        Integer l = minutesOfDay(likesUntil);
        if (m == null || l == null) {
            return explicit;
        }
        return l >= m ? 1 : 2;
    }

    /** Single-marker safe-removal day offset: strictly AFTER the liking-end — same day when the removal time is
     *  later in the day, otherwise the following day. Falls back to the explicit value when a time is unparseable. */
    private static Integer deriveTagRemoveDayOffset(String likesUntil, Integer likesOff, String remove, Integer explicit) {
        Integer l = minutesOfDay(likesUntil);
        Integer r = minutesOfDay(remove);
        if (l == null || r == null || likesOff == null) {
            return explicit;
        }
        return likesOff + (r > l ? 0 : 1);
    }
}

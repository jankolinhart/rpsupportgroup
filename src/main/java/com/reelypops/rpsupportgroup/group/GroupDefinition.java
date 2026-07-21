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
        if (type != SgType.CONTINUOUS) {
            return this;
        }
        List<Integer> days = (openWeekdays != null && !openWeekdays.isEmpty()) ? openWeekdays : continuousDays;
        return new GroupDefinition(type, timezone, markerOwners, maxTaggedPosts, days, startMarkerTime,
                endMarkerTime, endMarkerDayOffset, singleMarkerTime, likesUntilTime, likesUntilDayOffset,
                tagRemoveEarliestTime, tagRemoveEarliestDayOffset, days);
    }
}

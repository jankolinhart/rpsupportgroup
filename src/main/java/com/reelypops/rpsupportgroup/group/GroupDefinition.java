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
        Integer tagRemoveEarliestDayOffset) {

    /** Return a copy with a replaced marker-owner list (records are immutable). */
    public GroupDefinition withMarkerOwners(List<String> owners) {
        return new GroupDefinition(type, timezone, owners, maxTaggedPosts, continuousDays, startMarkerTime,
                endMarkerTime, endMarkerDayOffset, singleMarkerTime, likesUntilTime, likesUntilDayOffset,
                tagRemoveEarliestTime, tagRemoveEarliestDayOffset);
    }
}

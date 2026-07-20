package com.reelypops.rpsupportgroup.group;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The authoritative SG <em>definition</em> (Q2) — the group's shared truth that every ReelyPops client and the
 * scanner read <em>identically</em> to agree on round detection. Stored as jsonb on {@link SupportGroupConfig}
 * so it can evolve without a migration. User-local <em>preferences</em> (auto-like on/off, liking cadence,
 * notification prefs) live on the client and are never held here.
 *
 * @param type                  round-boundary type (§3)
 * @param timezone              IANA zone the group's rounds/opening times are evaluated in
 * @param markerOwners          IG handles whose marker posts delimit rounds (§2, §5b); grows via discovery (Q4)
 * @param openingDays           days the group is open (e.g. {@code MON}…{@code SUN}); empty = every day (§4)
 * @param maxTags               max tags a member may hold in a round, if the group caps it
 * @param safeTagRemovalMinutes minutes after round end before a tag may be safely removed
 * @param likingEndMinutes      minutes after round open by which liking duty must be complete
 */
public record GroupDefinition(
        @NotNull SgType type,
        @NotBlank String timezone,
        List<String> markerOwners,
        List<String> openingDays,
        Integer maxTags,
        Integer safeTagRemovalMinutes,
        Integer likingEndMinutes) {

    /** Return a copy with a replaced marker-owner list (records are immutable). */
    public GroupDefinition withMarkerOwners(List<String> owners) {
        return new GroupDefinition(type, timezone, owners, openingDays, maxTags, safeTagRemovalMinutes, likingEndMinutes);
    }
}

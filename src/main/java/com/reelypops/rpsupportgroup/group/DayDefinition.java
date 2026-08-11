package com.reelypops.rpsupportgroup.group;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One weekday's slice of the per-day vetted schedule (M4.5, vision §5c). The canonical model is a 7-slot weekly
 * schedule: each weekday is either OPEN with its own round shape + marker detector, or CLOSED (no round). Mirrors the
 * per-day fields of the flat {@link GroupDefinition} + {@link VettedProfile.DetectorArtifacts}; the group-global fields
 * (timezone, marker owners) stay on the parent {@link VettedProfile#definition()}.
 *
 * @param weekday                    weekday index (0=Sun … 6=Sat, matching {@link GroupDefinition#openWeekdays()})
 * @param open                       true when the group runs a round this day; false = CLOSED (the rest is ignored)
 * @param type                       this day's round type (set globally by the Portal today, stored per-day for the future)
 * @param startMarkerTime            {@code HH:mm} of the start marker (2-marker), else null
 * @param endMarkerTime              {@code HH:mm} of the end marker (2-marker), else null
 * @param endMarkerDayOffset         whole-day offset of the end marker from the start, else null
 * @param startMarkerDayOffset       whole days the start marker is posted relative to the round's NOMINAL (identity)
 *                                   weekday: null/0 = same day (default); -1 = the previous evening (a SWITCHER
 *                                   handoff, e.g. a Sunday start pre-posted on Saturday). Additive + nullable
 * @param singleMarkerTime           {@code HH:mm} of the single marker (single-marker), else null
 * @param likesUntilTime             {@code HH:mm} the liking duty must be complete by, else null
 * @param likesUntilDayOffset        day offset of the liking deadline, else null
 * @param tagRemoveEarliestTime      {@code HH:mm} earliest safe tag-removal, else null
 * @param tagRemoveEarliestDayOffset day offset of the safe tag-removal time, else null
 * @param maxTaggedPosts             max tagged posts a member may hold in this day's round, else null
 * @param style                      this day's marker style (flat-banner vs text-overlay)
 * @param references                 this day's typed marker references (start/end/single) — per-day per §5a
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DayDefinition(
        int weekday,
        boolean open,
        SgType type,
        String startMarkerTime,
        String endMarkerTime,
        Integer endMarkerDayOffset,
        Integer startMarkerDayOffset,
        String singleMarkerTime,
        String likesUntilTime,
        Integer likesUntilDayOffset,
        String tagRemoveEarliestTime,
        Integer tagRemoveEarliestDayOffset,
        Integer maxTaggedPosts,
        MarkerStyle style,
        List<VettedProfile.TypedMarkerReference> references) {

    /** The derived round CLASS (M5 A3): CROSS_DAY when this open TWO_MARKER day's end lands a day+ later; null when CLOSED or not TWO_MARKER. */
    @JsonProperty("roundClass")
    public RoundClass roundClass() {
        return open ? RoundClass.of(type == SgType.TWO_MARKER, endMarkerDayOffset) : null;
    }

    /**
     * This day's schedule flattened onto the group-global {@code base} (type / timezone / marker owners / continuous
     * days), carrying the group's open weekdays — the flat {@link GroupDefinition} projection (M4.5-e).
     */
    GroupDefinition toDefinition(GroupDefinition base, List<Integer> openWeekdays) {
        return new GroupDefinition(base.type(), base.timezone(), base.markerOwners(), maxTaggedPosts,
                base.continuousDays(), startMarkerTime, endMarkerTime, endMarkerDayOffset, singleMarkerTime,
                likesUntilTime, likesUntilDayOffset, tagRemoveEarliestTime, tagRemoveEarliestDayOffset, openWeekdays);
    }

    /** This day's marker detector (style + typed references) as the flat projection (M4.5-e). */
    VettedProfile.DetectorArtifacts toDetector() {
        return new VettedProfile.DetectorArtifacts(style, references == null ? List.of() : references);
    }

    /** A copy of this day carrying {@code refs} in place of its marker references (vet-time image-locator enrichment). */
    DayDefinition withReferences(List<VettedProfile.TypedMarkerReference> refs) {
        return new DayDefinition(weekday, open, type, startMarkerTime, endMarkerTime, endMarkerDayOffset,
                startMarkerDayOffset, singleMarkerTime, likesUntilTime, likesUntilDayOffset, tagRemoveEarliestTime,
                tagRemoveEarliestDayOffset, maxTaggedPosts, style, refs);
    }
}

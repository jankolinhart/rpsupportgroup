package com.reelypops.rpsupportgroup.group;

/**
 * The recognition style of a group's round markers, as detected by vetting (M3a). Kept in the {@code group} package
 * (not {@code vetting}) so the persisted {@link DetectedProfile} value object carries no dependency back into the
 * vetting pipeline — the vetting mapper converts its own {@code ProposedType} into this.
 */
public enum MarkerStyle {

    /** A recurring identical banner image — recognised by perceptual hash. */
    FLAT_BANNER,

    /** Per-post overlay text on a changing background — no image recurs; needs vision to read. */
    TEXT_OVERLAY,

    /** Not enough evidence to say (e.g. an empty snapshot). */
    UNKNOWN
}

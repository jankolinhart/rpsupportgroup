package com.reelypops.rpsupportgroup.vetting;

import java.util.List;
import java.util.UUID;

/**
 * An <em>advisory, fail-open</em> vetting proposal (P3): the pipeline turns one sealed corpus snapshot into a
 * <strong>proposed</strong> detector profile to pre-fill the admin form — it <strong>never</strong> auto-vets. Tier&nbsp;0
 * (local dHash clustering, no AI) fills this cheaply for flat-banner groups; text-overlay groups produce no cluster and
 * set {@link #escalate()} so a later Tier&nbsp;1/2 (LLM / vision) enricher can refine it. The human admin gate stays
 * authoritative.
 *
 * @param snapshotId     the snapshot this proposal was derived from
 * @param igAccount      the support group's Instagram account
 * @param itemCount      how many grid items were considered
 * @param proposedType   the inferred marker style
 * @param ownerRoster    candidate marker-owner usernames. On a confident accept this is the ONE owner; on an escalate
 *                       it is the ranked candidates (context for the AI/human); empty when nothing recurs cleanly
 * @param markerClusters the top single-owner recurring-image clusters (the flat-banner candidates)
 * @param confidence     0..1 — how strongly the top OWNER stands out from the next DIFFERENT owner:
 *                       min(separation, absolute-strength); a slam dunk clears the gate (accept), else it escalates
 * @param escalate       true when Tier&nbsp;0 could not decide (text-overlay) and vision escalation is warranted
 * @param provenance     which tier(s) produced this proposal
 */
public record DetectorProfileProposal(
        UUID snapshotId,
        String igAccount,
        int itemCount,
        ProposedType proposedType,
        List<String> ownerRoster,
        List<MarkerCluster> markerClusters,
        double confidence,
        boolean escalate,
        String provenance) {

    /** The inferred marker style. */
    public enum ProposedType {
        /** A recurring identical banner image (Tier 0 can propose it directly). */
        FLAT_BANNER,
        /** Per-post overlay text on a changing background — no image recurs; needs vision (Tier 1/2). */
        TEXT_OVERLAY,
        /** Not enough evidence to say (e.g. an empty snapshot). */
        UNKNOWN
    }

    /**
     * One recurring-image cluster within the grid — a flat-banner marker candidate.
     *
     * @param dHash            the cluster's representative perceptual hash
     * @param size             how many grid posts fell in this cluster
     * @param authorUsernames  the distinct authors who posted this image
     * @param sampleShortcodes a capped sample of the posts' shortcodes (for the admin to eyeball)
     */
    public record MarkerCluster(String dHash, int size, List<String> authorUsernames, List<String> sampleShortcodes) {
    }
}

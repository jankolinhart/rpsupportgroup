package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.group.DetectedProfile.MarkerReference;
import com.reelypops.rpsupportgroup.group.DetectedProfile.OwnerCandidate;
import com.reelypops.rpsupportgroup.group.DetectedProfile.ScheduleFacet;

import java.time.Instant;
import java.util.List;

/**
 * The full Tier&nbsp;0 read of a snapshot (M3b): the advisory {@link DetectorProfileProposal} plus the enrichment the
 * admin-only {@link com.reelypops.rpsupportgroup.group.DetectedProfile} carries — the ranked owner {@link #candidates}
 * with their marker-signature metrics, the recurring-image {@link #references} (each with its marker confidence), and
 * the derived {@link #schedule} (group type, round timing, opening days, current state).
 *
 * <p>Computed in one clustering pass by {@link VettingProposalService}; {@link VettingProposalService#propose} returns
 * only the {@link #proposal} for the legacy proposer surface, while {@code detect} maps the whole analysis onto the
 * persisted advisory.
 *
 * <p>{@code referencePostedAt} carries, parallel to {@link #references}, the sorted marker-post timestamps of each
 * reference cluster (the owner's posts) — the per-weekday occurrence source the AI-discovery request needs (M4.5).
 */
record SnapshotAnalysis(DetectorProfileProposal proposal,
                        List<OwnerCandidate> candidates,
                        List<MarkerReference> references,
                        ScheduleFacet schedule,
                        List<List<Instant>> referencePostedAt) {
}

package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.group.DetectedProfile;

/**
 * The result of one convergent {@link AiDiscoveryService#refineAiDiscovery refinement} pass: the (merged, re-persisted)
 * advisory and how many DISTINCT marker templates that pass ADDED. {@code added == 0} means the set has converged — the
 * admin discovery UI stops the loop and shows "Convergence reached". The pass's token spend + estimated cost rides on
 * {@code profile.aiDiscovery().usage()}, exactly as the metrics pass surfaces it.
 *
 * @param profile the updated advisory (with the complete-so-far marker set + this pass's usage)
 * @param added   how many new distinct marker templates this pass added (0 = converged)
 */
public record AiRefineResult(DetectedProfile profile, int added) {
}

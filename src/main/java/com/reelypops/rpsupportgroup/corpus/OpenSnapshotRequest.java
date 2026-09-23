package com.reelypops.rpsupportgroup.corpus;

import jakarta.validation.constraints.NotNull;

/**
 * Open a new corpus snapshot for a group (P1): the {@link CorpusSource} plus who is capturing the pass.
 *
 * <p>Three kinds of provenance, and they are not interchangeable. {@code capturedByAccount} is the Instagram
 * handle whose residential session walks the grid; {@code capturedByDevice} is the machine it runs on, which
 * only that machine knows; {@code capturedForUser} is the customer, and it is stamped by rpserver from the
 * validated token rather than sent by the client — provenance a caller could choose would not be provenance.</p>
 *
 * <p>All three are optional. A client too old to send them still produces good evidence, and a missing value
 * reads as "not recorded" rather than being guessed at.</p>
 */
public record OpenSnapshotRequest(
        @NotNull CorpusSource source,
        String capturedByAccount,
        String capturedByDevice,
        java.util.UUID capturedForUser) {
}

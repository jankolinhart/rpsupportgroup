package com.reelypops.rpsupportgroup.corpus;

import java.time.Instant;
import java.util.UUID;

/** The admin/BFF view of a corpus snapshot header (P1). */
public record SnapshotResponse(
        UUID id,
        String igAccount,
        CorpusSource source,
        SnapshotStatus status,
        int itemCount,
        String capturedByAccount,
        /** The machine that ran it, and the customer it ran for — null on a pass that predates provenance. */
        String capturedByDevice,
        UUID capturedForUser,
        Instant createdAt,
        Instant sealedAt,
        /** Why this pass was voided whole — {@code null} unless {@code status} is {@code REJECTED}. */
        String rejectedReason) {

    public static SnapshotResponse of(MarkerCorpusSnapshot s) {
        return new SnapshotResponse(s.getId(), s.getIgAccount(), s.getSource(), s.getStatus(), s.getItemCount(),
                s.getCapturedByAccount(), s.getCapturedByDevice(), s.getCapturedForUser(),
                s.getCreatedAt(), s.getSealedAt(), s.getRejectedReason());
    }
}

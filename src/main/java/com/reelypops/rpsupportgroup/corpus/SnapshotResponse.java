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
        Instant createdAt,
        Instant sealedAt) {

    public static SnapshotResponse of(MarkerCorpusSnapshot s) {
        return new SnapshotResponse(s.getId(), s.getIgAccount(), s.getSource(), s.getStatus(), s.getItemCount(),
                s.getCapturedByAccount(), s.getCreatedAt(), s.getSealedAt());
    }
}

package com.reelypops.rpsupportgroup.corpus;

import java.util.List;

/** A snapshot header plus its items in grid order (P1) — the admin vetting-evidence detail view. */
public record SnapshotDetailResponse(
        SnapshotResponse snapshot,
        List<CorpusItemResponse> items,
        List<String> representativeShortcodes) {

    public static SnapshotDetailResponse of(MarkerCorpusSnapshot snapshot, List<CorpusSnapshotItem> items,
                                            List<String> representativeShortcodes) {
        return new SnapshotDetailResponse(SnapshotResponse.of(snapshot),
                items.stream().map(CorpusItemResponse::of).toList(),
                representativeShortcodes);
    }
}

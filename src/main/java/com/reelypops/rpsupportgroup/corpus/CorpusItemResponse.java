package com.reelypops.rpsupportgroup.corpus;

import java.time.Instant;

/** The admin/BFF view of one corpus snapshot item (P1). */
public record CorpusItemResponse(
        String shortcode,
        String authorUsername,
        String dHash,
        Instant postedAt,
        int ordinal) {

    public static CorpusItemResponse of(CorpusSnapshotItem i) {
        return new CorpusItemResponse(i.getShortcode(), i.getAuthorUsername(), i.getDHash(), i.getPostedAt(),
                i.getOrdinal());
    }
}

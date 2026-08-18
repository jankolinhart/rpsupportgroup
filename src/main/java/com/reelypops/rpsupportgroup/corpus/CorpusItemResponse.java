package com.reelypops.rpsupportgroup.corpus;

import java.time.Instant;

/** The admin/BFF view of one corpus snapshot item (P1). */
public record CorpusItemResponse(
        String shortcode,
        String authorUsername,
        String dHash,
        Instant postedAt,
        int ordinal,
        /**
         * ⚠️ Burned: this post may never be offered as a marker-reference candidate. The Vetting Portal must not
         * let it be clicked — picking it writes a fingerprint into a vetted profile that no client can match, and
         * nothing downstream would ever say so.
         */
        boolean unusable,
        String unusableReason) {

    public static CorpusItemResponse of(CorpusSnapshotItem i) {
        return new CorpusItemResponse(i.getShortcode(), i.getAuthorUsername(), i.getDHash(), i.getPostedAt(),
                i.getOrdinal(), i.isUnusable(), i.getUnusableReason());
    }
}

package com.reelypops.rpsupportgroup.group;

/** A content category as returned by the API — the stable {@code slug} + its display {@code label}. */
public record CategoryResponse(String slug, String label) {

    static CategoryResponse of(SgCategory c) {
        return new CategoryResponse(c.getSlug(), c.getLabel());
    }
}

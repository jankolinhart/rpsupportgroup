package com.reelypops.rpsupportgroup.vetting;

/**
 * One row of the recent tagged-grid window sent to the AI (M4.7): the grid {@code ordinal} (taggedAt order — newest tag
 * first, directive P5), the row's {@code author}, and whether it is a {@code marker} (a post by the proposed marker
 * owner). Unlike the per-cluster summary — which loses the sequence — this preserves the true grid ORDER, so the model
 * can reconstruct rounds (a START→ENDE marker pair brackets one round) and count the member posts inside each round
 * (the per-member {@code maxTaggedPosts} cap). One row per (post × author), mirroring the corpus (directive P4).
 */
record GridRow(int ordinal, String author, boolean marker) {
}

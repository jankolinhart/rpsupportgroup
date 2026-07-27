package com.reelypops.rpsupportgroup.vetting;

import java.time.Instant;
import java.util.List;

/**
 * The broadened marker evidence sent to the AI-discovery model (M4.6 tuning). Unlike {@link SnapshotAnalysis#references}
 * — the Tier-0 DISPLAY set, gated at {@code rp.vetting.min-score} so only clean, high-signal markers surface — this
 * carries EVERY recurring-image cluster of the PROPOSED marker owner, INCLUDING the sub-threshold fragments a
 * high-variation banner shatters into (e.g. a "START" caption over many different product photos splits the perceptual
 * hash into several weaker clusters that each miss the score gate, so only a single clean "END" banner reaches the AI
 * and it reports the start marker "not present in the data"). Each sample pairs a representative image
 * ({@link #sampleShortcodes}, first entry is image-backed when one was captured) with the owner's post
 * {@link #postedAt} timestamps, so the model can read the marker text the local hash pass cannot rank and bucket the
 * occurrences per weekday.
 */
record AiMarkerSample(int distinctPosts,
                      String author,
                      int recurrence,
                      double cadenceRegularity,
                      double coverage,
                      double score,
                      List<String> sampleShortcodes,
                      List<Instant> postedAt) {
}

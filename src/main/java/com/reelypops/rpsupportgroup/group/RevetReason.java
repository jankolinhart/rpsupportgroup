package com.reelypops.rpsupportgroup.group;

import java.time.Instant;

/**
 * The aggregated "why" behind a config's derived needs-re-vet flag (M5 re-vet consumer): how many distinct home
 * clients reported the marker-disagree drift, the total number of reports, the latest agree/disagree tally, the max
 * client-side persistence count, and the first/last time it was seen. Rendered by the admin console. {@code null} on a
 * {@link GroupResponse} when the config does not need re-vetting.
 */
public record RevetReason(
        int distinctReporters,
        long totalOccurrences,
        Integer latestAgreePass,
        Integer latestDisagreePass,
        Integer maxPersistenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt) {
}

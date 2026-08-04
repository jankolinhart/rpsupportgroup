package com.reelypops.rpsupportgroup.group;

import java.time.Instant;
import java.util.List;

/**
 * One aggregated "why" behind a config's derived needs-re-vet flag (M5 re-vet consumer), tagged by {@link #kind}
 * so the admin console can render it distinctly. A config may need re-vetting for more than one kind at once, so
 * {@link SupportGroupConfigService.RevetStatus} carries a LIST of these.
 *
 * <ul>
 *   <li>{@link DriftKind#MARKER_DISAGREE}: how many distinct home clients reported the disagree drift, the total
 *       report count, the latest agree/disagree tally, and the max client-side persistence count.
 *       {@link #nominatedOwnerHandles} is empty.</li>
 *   <li>{@link DriftKind#NEW_OWNER}: the distinct {@link #nominatedOwnerHandles} clients nominated as new marker
 *       owners, plus the reporter/occurrence counts. The marker-disagree tally fields are {@code null}.</li>
 * </ul>
 *
 * <p>Both carry the first/last time the drift was seen. Empty list on a {@link GroupResponse} when the config does
 * not need re-vetting.
 */
public record RevetReason(
        DriftKind kind,
        int distinctReporters,
        long totalOccurrences,
        Integer latestAgreePass,
        Integer latestDisagreePass,
        Integer maxPersistenceCount,
        List<String> nominatedOwnerHandles,
        Instant firstSeenAt,
        Instant lastSeenAt) {
}

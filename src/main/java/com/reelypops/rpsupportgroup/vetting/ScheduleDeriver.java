package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.group.DetectedProfile.RoundTime;
import com.reelypops.rpsupportgroup.group.DetectedProfile.ScheduleFacet;
import com.reelypops.rpsupportgroup.group.MarkerGroupType;
import com.reelypops.rpsupportgroup.group.RoundState;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Derives the group's <strong>schedule advisory</strong> (M3b, {@code marker-auto-discovery.md} §3.5 / §4.1) from the
 * accepted owner's marker clusters — no AI. A marker's {@code postedAt} <em>is</em> the round-boundary time (the owner
 * posts a fresh marker each boundary, so {@code postedAt ≈ taggedAt} holds for markers), so the corpus yields the
 * schedule for free:
 *
 * <ul>
 *   <li><b>Group type</b> — one owner cluster ⇒ SINGLE_MARKER, two ⇒ TWO_MARKER; confidence from symmetry + pairing.</li>
 *   <li><b>Start/End labels</b> — of the two clusters, whichever consistently <em>precedes</em> the other within a round
 *       is START (temporal alternation, without reading the images).</li>
 *   <li><b>Round times</b> — the circular mean time-of-day (UTC) of each cluster's {@code postedAt}; confidence = how
 *       tight that distribution is. Timing needs ≥2 dated posts, else confidence 0 (judgment point 2).</li>
 *   <li><b>Opening days</b> — the START cluster's weekdays (2-marker only); single-marker is always-open.</li>
 *   <li><b>Current state</b> — the trailing marker: a trailing START ⇒ round OPEN, a trailing END ⇒ CLOSED_PERIOD.</li>
 * </ul>
 *
 * <p>Times are tz-agnostic UTC until the admin sets the group timezone in the Vetting Portal. Everything is advisory and
 * independently scored — a clean owner with scattered times yields high owner-confidence but low timing-confidence.
 */
final class ScheduleDeriver {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private ScheduleDeriver() {
    }

    /** One of the accepted owner's marker clusters, reduced to the post positions the derivation needs. */
    record ClusterPosts(List<Post> posts) {
        int recurrence() {
            return posts.size();
        }
    }

    /** One marker post's grid position + when it was posted ({@code postedAt} may be null when the scrape missed it). */
    record Post(int ordinal, Instant postedAt) {
    }

    /** The empty advisory when there is no clean owner to derive a schedule from. */
    static ScheduleFacet empty() {
        return new ScheduleFacet(MarkerGroupType.UNKNOWN, 0.0, null, null, null, List.of(), 0.0, RoundState.UNKNOWN, null);
    }

    /** Derive the schedule from the accepted owner's clusters (ordered strongest-first). */
    static ScheduleFacet derive(List<ClusterPosts> ownerClusters) {
        return switch (ownerClusters.size()) {
            case 0 -> empty();
            case 1 -> single(ownerClusters.get(0));
            case 2 -> two(ownerClusters.get(0), ownerClusters.get(1));
            default -> ambiguous();
        };
    }

    /** One cluster ⇒ a single-marker group: one round time, always-open (the marker only delimits transitions). */
    private static ScheduleFacet single(ClusterPosts cluster) {
        RoundTime single = time(cluster.posts());
        double confidence = Math.min(1.0, cluster.recurrence() / 3.0);
        // A single-marker group has no END, so it is always open — no opening-day restriction or end offset to derive.
        return new ScheduleFacet(MarkerGroupType.SINGLE_MARKER, confidence, null, null, single,
                List.of(), 0.0, RoundState.OPEN, null);
    }

    /** Two clusters ⇒ a two-marker group: label START/END by alternation, then derive both times + opening days. */
    private static ScheduleFacet two(ClusterPosts a, ClusterPosts b) {
        double symmetry = 1.0 - (double) Math.abs(a.recurrence() - b.recurrence())
                / Math.max(a.recurrence(), b.recurrence());
        List<Tagged> ordered = datedByTime(a, b);
        double pairing = pairing(ordered);
        double groupTypeConfidence = (symmetry + pairing) / 2.0;

        Labelled labelled = labelStartEnd(a, b, ordered);
        RoundTime start = time(labelled.start().posts());
        RoundTime end = time(labelled.end().posts());
        // Round-open duration = the typical START → END gap ⇒ the end-marker day offset (0 when END is same-day).
        Integer endMarkerDayOffset = dayOffset(medianGap(ordered, labelled.startIndex(), 1 - labelled.startIndex()));
        List<Integer> openWeekdays = weekdays(labelled.start().posts());
        double openingDaysConfidence = openingDaysConfidence(openWeekdays, datedCount(labelled.start()));
        RoundState state = currentState(ordered, labelled);
        return new ScheduleFacet(MarkerGroupType.TWO_MARKER, groupTypeConfidence, start, end, null,
                openWeekdays, openingDaysConfidence, state, endMarkerDayOffset);
    }

    /** Three or more owner clusters is ambiguous (marker types vs per-weekday variants) — do not guess a schedule. */
    private static ScheduleFacet ambiguous() {
        return new ScheduleFacet(MarkerGroupType.UNKNOWN, 0.0, null, null, null, List.of(), 0.0, RoundState.UNKNOWN, null);
    }

    /**
     * Decide which cluster is START by temporal alternation: the one that more often immediately precedes the other
     * (a START is followed by its END within the round). This labels correctly whether the round is open longer or
     * shorter than it is closed; the confidence for facets that depend on it comes from their own signal, not from this
     * alternation count (see {@link #openingDaysConfidence}).
     */
    private static Labelled labelStartEnd(ClusterPosts a, ClusterPosts b, List<Tagged> ordered) {
        int aThenB = 0;
        int bThenA = 0;
        for (int i = 1; i < ordered.size(); i++) {
            int prev = ordered.get(i - 1).cluster();
            int cur = ordered.get(i).cluster();
            if (prev == 0 && cur == 1) {
                aThenB++;
            } else if (prev == 1 && cur == 0) {
                bThenA++;
            }
        }
        // A START marker is followed by its END within the round, so the cluster with more "→ other" transitions leads.
        boolean aIsStart = aThenB >= bThenA;
        return aIsStart ? new Labelled(a, b, 0) : new Labelled(b, a, 1);
    }

    /** Median gap (minutes) of consecutive {@code from → to} cluster transitions in time order; 0 when none. */
    private static double medianGap(List<Tagged> ordered, int from, int to) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 0; i < ordered.size() - 1; i++) {
            if (ordered.get(i).cluster() == from && ordered.get(i + 1).cluster() == to) {
                gaps.add(Duration.between(ordered.get(i).postedAt(), ordered.get(i + 1).postedAt()).toMinutes());
            }
        }
        return median(gaps);
    }

    /** The round-open duration in whole days (the end-marker offset from the start); null when it cannot be measured. */
    private static Integer dayOffset(double openGapMinutes) {
        if (openGapMinutes <= 0) {
            return null;
        }
        return (int) Math.round(openGapMinutes / MINUTES_PER_DAY);
    }

    /**
     * Opening-days confidence — how well-backed the derived opening weekdays are, independent of the START/END label.
     * Rewards RECURRENCE: ~2+ dated START posts per opening weekday ⇒ full confidence. (The old formula multiplied by
     * the alternation label confidence, which is ≈0 for a perfectly-alternating — i.e. perfectly regular — group and so
     * wrongly tanked this to zero.)
     */
    private static double openingDaysConfidence(List<Integer> openWeekdays, int datedStart) {
        if (openWeekdays.isEmpty()) {
            return 0.0;
        }
        return Math.min(1.0, (datedStart / (double) openWeekdays.size()) / 2.0);
    }

    /** Median of a list of gap lengths (minutes); 0 when empty. */
    private static double median(List<Long> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /** The current round state from the trailing (latest) dated marker: START ⇒ open, END ⇒ closed period. */
    private static RoundState currentState(List<Tagged> ordered, Labelled labelled) {
        if (ordered.isEmpty()) {
            return RoundState.UNKNOWN;
        }
        Tagged trailing = ordered.get(ordered.size() - 1);
        boolean trailingIsStart = labelled.startIndex() == trailing.cluster();
        return trailingIsStart ? RoundState.OPEN : RoundState.CLOSED_PERIOD;
    }

    /** The circular-mean time-of-day (UTC) of the cluster's dated posts; confidence = tightness (0 if &lt; 2 dated). */
    private static RoundTime time(List<Post> posts) {
        List<Integer> minutes = posts.stream()
                .map(Post::postedAt)
                .filter(java.util.Objects::nonNull)
                .map(ScheduleDeriver::minuteOfDayUtc)
                .toList();
        if (minutes.isEmpty()) {
            return null;
        }
        double sumSin = 0.0;
        double sumCos = 0.0;
        for (int m : minutes) {
            double angle = 2 * Math.PI * m / MINUTES_PER_DAY;
            sumSin += Math.sin(angle);
            sumCos += Math.cos(angle);
        }
        double meanAngle = Math.atan2(sumSin / minutes.size(), sumCos / minutes.size());
        int meanMinute = (int) Math.round(meanAngle / (2 * Math.PI) * MINUTES_PER_DAY);
        meanMinute = ((meanMinute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
        double resultant = Math.sqrt(sumSin * sumSin + sumCos * sumCos) / minutes.size();
        double confidence = minutes.size() < 2 ? 0.0 : resultant;
        return new RoundTime(String.format("%02d:%02d", meanMinute / 60, meanMinute % 60), confidence);
    }

    /** The distinct UTC weekdays (0=Sun … 6=Sat) the cluster's dated posts fall on, sorted. */
    private static List<Integer> weekdays(List<Post> posts) {
        return posts.stream()
                .map(Post::postedAt)
                .filter(java.util.Objects::nonNull)
                .map(instant -> instant.atZone(ZoneOffset.UTC).getDayOfWeek().getValue() % 7)
                .distinct()
                .sorted()
                .toList();
    }

    /** Both clusters' dated posts, tagged with their cluster index (0/1) and ordered by post time. */
    private static List<Tagged> datedByTime(ClusterPosts a, ClusterPosts b) {
        List<Tagged> tagged = new ArrayList<>();
        for (Post p : a.posts()) {
            if (p.postedAt() != null) {
                tagged.add(new Tagged(0, p.postedAt()));
            }
        }
        for (Post p : b.posts()) {
            if (p.postedAt() != null) {
                tagged.add(new Tagged(1, p.postedAt()));
            }
        }
        tagged.sort(Comparator.comparing(Tagged::postedAt));
        return tagged;
    }

    /** How often the two clusters alternate in time (1 = perfect A,B,A,B pairing; 0 = clumped) — a marker-type signal. */
    private static double pairing(List<Tagged> ordered) {
        if (ordered.size() < 2) {
            return 0.0;
        }
        int alternations = 0;
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).cluster() != ordered.get(i - 1).cluster()) {
                alternations++;
            }
        }
        return (double) alternations / (ordered.size() - 1);
    }

    private static int datedCount(ClusterPosts cluster) {
        return (int) cluster.posts().stream().map(Post::postedAt).filter(java.util.Objects::nonNull).count();
    }

    private static int minuteOfDayUtc(Instant instant) {
        var time = instant.atZone(ZoneOffset.UTC).toLocalTime();
        return time.getHour() * 60 + time.getMinute();
    }

    /** A dated post tagged with which of the two clusters (0 or 1) it belongs to. */
    private record Tagged(int cluster, Instant postedAt) {
    }

    /** The two clusters after labelling: which is START, which is END, and the START cluster's index (0 or 1). */
    private record Labelled(ClusterPosts start, ClusterPosts end, int startIndex) {
    }
}

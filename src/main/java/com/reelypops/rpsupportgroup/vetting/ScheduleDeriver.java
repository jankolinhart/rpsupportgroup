package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.group.DetectedProfile.RoundTime;
import com.reelypops.rpsupportgroup.group.DetectedProfile.ScheduleFacet;
import com.reelypops.rpsupportgroup.group.MarkerGroupType;
import com.reelypops.rpsupportgroup.group.RoundState;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Derives the group's <strong>schedule advisory</strong> (M3b, {@code marker-auto-discovery.md} §3.5 / §4.1) from the
 * accepted owner's marker clusters — no AI. A marker's {@code postedAt} <em>is</em> the round-boundary time (the owner
 * posts a fresh marker each boundary, so {@code postedAt ≈ taggedAt} holds for markers), so the corpus yields the
 * schedule for free:
 *
 * <ul>
 *   <li><b>Group type</b> — one owner cluster ⇒ SINGLE_MARKER, two ⇒ TWO_MARKER; confidence from symmetry + pairing.</li>
 *   <li><b>Start/End labels</b> — the OPEN round (START → END) is the longer of the two clusters' median gaps, since an
 *       active group is open at least as long as it is closed; the cluster followed by that longer gap is START. (A
 *       rare mostly-closed group can invert this — the admin corrects it via the reference selector.)</li>
 *   <li><b>Round times</b> — the circular mean time-of-day (UTC) of each cluster's {@code postedAt}; confidence = how
 *       tight that distribution is. Timing needs ≥2 dated posts, else confidence 0 (judgment point 2).</li>
 *   <li><b>End day offset</b> — the median whole-day gap from a START to its paired END (0 when the round opens + closes
 *       on the same calendar day).</li>
 *   <li><b>Opening days</b> — the <em>open span</em>: every weekday a round is active (START → END), so a round that
 *       spans a weekend marks Sat + Sun open even though no marker was posted then. Confidence = how consistently the
 *       rounds cover the same weekday set.</li>
 *   <li><b>Current state</b> — the trailing marker: a trailing START ⇒ round OPEN, a trailing END ⇒ CLOSED_PERIOD.</li>
 * </ul>
 *
 * <p>Times are tz-agnostic UTC until the admin sets the group timezone in the Vetting Dashboard. Everything is advisory
 * and independently scored — a clean owner with scattered times yields high owner-confidence but low timing-confidence.
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
        return new ScheduleFacet(MarkerGroupType.UNKNOWN, 0.0, null, null, null, List.of(), 0.0,
                RoundState.UNKNOWN, null, 0.0, 0.0, 0);
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
                List.of(), 0.0, RoundState.OPEN, null, 0.0, 0.0, cluster.recurrence());
    }

    /** Two clusters ⇒ a two-marker group: label START/END, reconstruct rounds, then derive times, offset + open span. */
    private static ScheduleFacet two(ClusterPosts a, ClusterPosts b) {
        double symmetry = 1.0 - (double) Math.abs(a.recurrence() - b.recurrence())
                / Math.max(a.recurrence(), b.recurrence());
        List<Tagged> ordered = datedByTime(a, b);
        double pairing = pairing(ordered);
        double groupTypeConfidence = (symmetry + pairing) / 2.0;

        Labelled labelled = labelStartEnd(a, b, ordered);
        RoundTime start = time(labelled.start().posts());
        RoundTime end = time(labelled.end().posts());
        List<Round> rounds = reconstructRounds(ordered, labelled.startIndex());
        Integer endMarkerDayOffset = endDayOffset(rounds);
        List<Integer> openWeekdays = openSpan(rounds);
        double openingDaysConfidence = spanConsistency(rounds, openWeekdays);
        RoundState state = currentState(ordered, labelled);
        return new ScheduleFacet(MarkerGroupType.TWO_MARKER, groupTypeConfidence, start, end, null,
                openWeekdays, openingDaysConfidence, state, endMarkerDayOffset, symmetry, pairing, rounds.size());
    }

    /** Three or more owner clusters is ambiguous (marker types vs per-weekday variants) — do not guess a schedule. */
    private static ScheduleFacet ambiguous() {
        return new ScheduleFacet(MarkerGroupType.UNKNOWN, 0.0, null, null, null, List.of(), 0.0,
                RoundState.UNKNOWN, null, 0.0, 0.0, 0);
    }

    /**
     * Decide which cluster is START from the GAP STRUCTURE: the OPEN round (START → END) is the longer of the two
     * clusters' median "gap to the next opposite marker", since an active group is open at least as long as it is
     * closed (for a contiguous group the owner posts END + the next START together, so the closed gap ≈ 0 and the open
     * round is far longer). The cluster followed by that longer gap is START. A rare mostly-closed group inverts this —
     * the admin corrects it with the reference selector.
     */
    private static Labelled labelStartEnd(ClusterPosts a, ClusterPosts b, List<Tagged> ordered) {
        double aToB = medianGapMinutes(ordered, 0, 1);
        double bToA = medianGapMinutes(ordered, 1, 0);
        boolean aIsStart = aToB >= bToA;
        return aIsStart ? new Labelled(a, b, 0) : new Labelled(b, a, 1);
    }

    /** Median gap (fractional minutes — a sub-minute boundary is NOT truncated to 0) of {@code from → to} transitions. */
    private static double medianGapMinutes(List<Tagged> ordered, int from, int to) {
        List<Long> seconds = new ArrayList<>();
        for (int i = 0; i < ordered.size() - 1; i++) {
            if (ordered.get(i).cluster() == from && ordered.get(i + 1).cluster() == to) {
                seconds.add(Duration.between(ordered.get(i).postedAt(), ordered.get(i + 1).postedAt()).toSeconds());
            }
        }
        return median(seconds) / 60.0;
    }

    /**
     * Reconstruct the OPEN rounds: walk the markers in time order; a START opens a round and the next END closes it (a
     * repeated START before any END just moves the open time forward). Each round is a [start, end] instant pair.
     */
    private static List<Round> reconstructRounds(List<Tagged> ordered, int startIndex) {
        List<Round> rounds = new ArrayList<>();
        Instant openAt = null;
        for (Tagged t : ordered) {
            if (t.cluster() == startIndex) {
                openAt = t.postedAt();
            } else if (openAt != null) {
                rounds.add(new Round(openAt, t.postedAt()));
                openAt = null;
            }
        }
        return rounds;
    }

    /** The end-marker day offset = median whole-day gap from a START to its paired END (0 when the same calendar day). */
    private static Integer endDayOffset(List<Round> rounds) {
        if (rounds.isEmpty()) {
            return null;
        }
        List<Long> days = rounds.stream()
                .map(r -> ChronoUnit.DAYS.between(utcDate(r.start()), utcDate(r.end())))
                .toList();
        return (int) Math.round(median(days));
    }

    /** The OPEN-SPAN weekdays: every weekday any round is active START → END (a round over a weekend marks Sat+Sun). */
    private static List<Integer> openSpan(List<Round> rounds) {
        Set<Integer> days = new TreeSet<>();
        for (Round r : rounds) {
            days.addAll(weekdaysBetween(r.start(), r.end()));
            if (days.size() == 7) {
                break;
            }
        }
        return new ArrayList<>(days);
    }

    /**
     * Opening-days confidence = how CONSISTENTLY the rounds cover the derived open span (not how many days): the average
     * fraction of the span each round covers. Every round covering the full span ⇒ 1.0; scattered rounds ⇒ lower.
     */
    private static double spanConsistency(List<Round> rounds, List<Integer> span) {
        if (rounds.isEmpty() || span.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (Round r : rounds) {
            Set<Integer> rd = weekdaysBetween(r.start(), r.end());
            long covered = span.stream().filter(rd::contains).count();
            total += (double) covered / span.size();
        }
        return total / rounds.size();
    }

    /** The UTC weekdays (0=Sun … 6=Sat) spanned from {@code start} to {@code end} inclusive (all 7 once the span ≥ 6). */
    private static Set<Integer> weekdaysBetween(Instant start, Instant end) {
        Set<Integer> days = new TreeSet<>();
        long span = ChronoUnit.DAYS.between(utcDate(start), utcDate(end));
        if (span >= 6) {
            for (int w = 0; w < 7; w++) {
                days.add(w);
            }
            return days;
        }
        LocalDate d = utcDate(start);
        for (long i = 0; i <= span; i++) {
            days.add(d.plusDays(i).getDayOfWeek().getValue() % 7);
        }
        return days;
    }

    private static LocalDate utcDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** Median of a list of longs; 0 when empty. */
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

    private static int minuteOfDayUtc(Instant instant) {
        var time = instant.atZone(ZoneOffset.UTC).toLocalTime();
        return time.getHour() * 60 + time.getMinute();
    }

    /** A dated post tagged with which of the two clusters (0 or 1) it belongs to. */
    private record Tagged(int cluster, Instant postedAt) {
    }

    /** One reconstructed open round: the START instant and its paired END instant (END is at/after START). */
    private record Round(Instant start, Instant end) {
    }

    /** The two clusters after labelling: which is START, which is END, and the START cluster's index (0 or 1). */
    private record Labelled(ClusterPosts start, ClusterPosts end, int startIndex) {
    }
}

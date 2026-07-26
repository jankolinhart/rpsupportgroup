package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.group.DetectedProfile.ScheduleFacet;
import com.reelypops.rpsupportgroup.group.MarkerGroupType;
import com.reelypops.rpsupportgroup.group.RoundState;
import com.reelypops.rpsupportgroup.vetting.ScheduleDeriver.ClusterPosts;
import com.reelypops.rpsupportgroup.vetting.ScheduleDeriver.Post;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the M3b schedule derivation (§3.5 / §4.1): from the accepted owner's marker clusters it reads the group
 * type, the START/END (or single) round times by temporal alternation, the opening weekdays, and the current round
 * state — all tz-agnostic in UTC. Timing needs ≥2 dated posts, else its confidence is 0.
 *
 * <p>Dates used: 2026-01-05 is a Monday, -06 Tuesday, -07 Wednesday (UTC), giving weekday codes 1/2/3 (0=Sun … 6=Sat).
 */
class ScheduleDeriverTest {

    private static Post post(int ordinal, String instant) {
        return new Post(ordinal, Instant.parse(instant));
    }

    private static Post undated(int ordinal) {
        return new Post(ordinal, null);
    }

    @Test
    void noOwnerClustersIsEmptyUnknown() {
        ScheduleFacet s = ScheduleDeriver.derive(List.of());

        assertThat(s.groupType()).isEqualTo(MarkerGroupType.UNKNOWN);
        assertThat(s.groupTypeConfidence()).isZero();
        assertThat(s.start()).isNull();
        assertThat(s.end()).isNull();
        assertThat(s.single()).isNull();
        assertThat(s.openWeekdays()).isEmpty();
        assertThat(s.openingDaysConfidence()).isZero();
        assertThat(s.currentState()).isEqualTo(RoundState.UNKNOWN);
    }

    @Test
    void singleMarkerDerivesOneTimeAndIsAlwaysOpen() {
        ClusterPosts cluster = new ClusterPosts(List.of(
                post(0, "2026-01-05T09:00:00Z"), post(1, "2026-01-06T09:05:00Z"), post(2, "2026-01-07T08:55:00Z")));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(cluster));

        assertThat(s.groupType()).isEqualTo(MarkerGroupType.SINGLE_MARKER);
        assertThat(s.groupTypeConfidence()).isEqualTo(1.0); // min(1, 3/3)
        assertThat(s.single().timeOfDayUtc()).isEqualTo("09:00");
        assertThat(s.single().confidence()).isGreaterThan(0.9); // tight cluster of times
        assertThat(s.start()).isNull();
        assertThat(s.end()).isNull();
        assertThat(s.openWeekdays()).isEmpty(); // single-marker is always-open — no restriction to derive
        assertThat(s.currentState()).isEqualTo(RoundState.OPEN);
    }

    @Test
    void singleMarkerTimeConfidenceIsZeroWithOnlyOneDatedPost() {
        ClusterPosts cluster = new ClusterPosts(List.of(post(0, "2026-01-05T09:00:00Z"), undated(1)));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(cluster));

        assertThat(s.single().timeOfDayUtc()).isEqualTo("09:00");
        assertThat(s.single().confidence()).isZero(); // < 2 dated posts (judgment point 2)
        assertThat(s.groupTypeConfidence()).isCloseTo(0.667, org.assertj.core.data.Offset.offset(0.01)); // min(1, 2/3)
    }

    @Test
    void twoMarkerLabelsStartAndEndByAlternationAndDerivesTimesAndDays() {
        // START ~09:00, END ~17:00, alternating across Mon/Tue; trailing post is an END → closed period.
        ClusterPosts startCluster = new ClusterPosts(List.of(
                post(0, "2026-01-05T09:00:00Z"), post(2, "2026-01-06T09:00:00Z")));
        ClusterPosts endCluster = new ClusterPosts(List.of(
                post(1, "2026-01-05T17:00:00Z"), post(3, "2026-01-06T17:00:00Z")));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(startCluster, endCluster));

        assertThat(s.groupType()).isEqualTo(MarkerGroupType.TWO_MARKER);
        assertThat(s.groupTypeConfidence()).isGreaterThan(0.9); // equal recurrence + perfect alternation
        assertThat(s.start().timeOfDayUtc()).isEqualTo("09:00");
        assertThat(s.end().timeOfDayUtc()).isEqualTo("17:00");
        assertThat(s.single()).isNull();
        assertThat(s.openWeekdays()).containsExactly(1, 2); // START posts on Mon + Tue
        assertThat(s.openingDaysConfidence()).isGreaterThan(0.0);
        assertThat(s.currentState()).isEqualTo(RoundState.CLOSED_PERIOD); // trailing marker is an END
    }

    @Test
    void twoMarkerReversedAlternationLabelsTheOtherClusterStart() {
        // The FIRST cluster's posts come LATER each round, so the SECOND cluster is START (covers the else branch).
        ClusterPosts lateCluster = new ClusterPosts(List.of(
                post(1, "2026-01-05T17:00:00Z"), post(3, "2026-01-06T17:00:00Z")));
        ClusterPosts earlyCluster = new ClusterPosts(List.of(
                post(0, "2026-01-05T09:00:00Z"), post(2, "2026-01-06T09:00:00Z")));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(lateCluster, earlyCluster));

        assertThat(s.start().timeOfDayUtc()).isEqualTo("09:00"); // the early cluster won START
        assertThat(s.end().timeOfDayUtc()).isEqualTo("17:00");
        assertThat(s.currentState()).isEqualTo(RoundState.CLOSED_PERIOD);
    }

    @Test
    void twoMarkerTrailingStartMeansRoundOpen() {
        // Sequence ends on a START with no END after it → a round is currently open.
        ClusterPosts startCluster = new ClusterPosts(List.of(
                post(0, "2026-01-05T09:00:00Z"), post(2, "2026-01-06T09:00:00Z")));
        ClusterPosts endCluster = new ClusterPosts(List.of(post(1, "2026-01-05T17:00:00Z")));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(startCluster, endCluster));

        assertThat(s.currentState()).isEqualTo(RoundState.OPEN); // last post is the Tue START
    }

    @Test
    void twoMarkerWithNoDatedPostsCannotLabelOrTime() {
        ClusterPosts a = new ClusterPosts(List.of(undated(0), undated(2)));
        ClusterPosts b = new ClusterPosts(List.of(undated(1), undated(3)));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(a, b));

        assertThat(s.groupType()).isEqualTo(MarkerGroupType.TWO_MARKER);
        assertThat(s.start()).isNull();
        assertThat(s.end()).isNull();
        assertThat(s.openWeekdays()).isEmpty();
        assertThat(s.openingDaysConfidence()).isZero();
        assertThat(s.currentState()).isEqualTo(RoundState.UNKNOWN);
    }

    @Test
    void threeOrMoreOwnerClustersIsAmbiguous() {
        ClusterPosts c = new ClusterPosts(List.of(post(0, "2026-01-05T09:00:00Z"), post(1, "2026-01-06T09:00:00Z")));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(c, c, c));

        assertThat(s.groupType()).isEqualTo(MarkerGroupType.UNKNOWN);
        assertThat(s.groupTypeConfidence()).isZero();
        assertThat(s.start()).isNull();
        assertThat(s.currentState()).isEqualTo(RoundState.UNKNOWN);
    }

    @Test
    void roundTimeUsesCircularMeanAcrossMidnight() {
        // 23:50 and 00:10 average to ~00:00, not ~12:00 — the mean must be circular.
        ClusterPosts cluster = new ClusterPosts(List.of(
                post(0, "2026-01-05T23:50:00Z"), post(1, "2026-01-06T00:10:00Z")));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(cluster));

        assertThat(s.single().timeOfDayUtc()).isEqualTo("00:00");
    }

    @Test
    void openingDaysAreDistinctAndSorted() {
        // START posts on Tue, Mon, Mon → weekdays {1,2} distinct + sorted, never duplicated.
        ClusterPosts startCluster = new ClusterPosts(List.of(
                post(0, "2026-01-06T09:00:00Z"), post(2, "2026-01-05T09:00:00Z"), post(4, "2026-01-12T09:00:00Z")));
        ClusterPosts endCluster = new ClusterPosts(List.of(
                post(1, "2026-01-06T17:00:00Z"), post(3, "2026-01-05T17:00:00Z")));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(startCluster, endCluster));

        assertThat(s.openWeekdays()).containsExactly(1, 2); // Mon(1) + Tue(2), each once
    }
}

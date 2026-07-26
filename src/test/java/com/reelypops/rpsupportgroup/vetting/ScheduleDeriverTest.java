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
import static org.assertj.core.data.Offset.offset;

/**
 * Unit test for the M3b + round-3 schedule derivation. From the accepted owner's marker clusters it derives the group
 * type (symmetry + pairing), the START/END round times, the end-marker day offset, and the <strong>open-span</strong>
 * opening weekdays — every weekday a round is active START→END, so a round that spans a weekend marks Sat+Sun open even
 * with no marker posted then. START/END are labelled by the gap structure: the OPEN round is the LONGER of the two
 * clusters' median gaps (an active group is open ≥ as long as it is closed).
 *
 * <p>Dates: 2026-01-04 is a Sunday, -05 Mon, -06 Tue, -07 Wed, -08 Thu, -09 Fri, -10 Sat, -11 Sun (UTC); weekday codes
 * 0=Sun … 6=Sat.
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
        assertThat(s.endMarkerDayOffset()).isNull();
        assertThat(s.symmetry()).isZero();
        assertThat(s.pairing()).isZero();
        assertThat(s.roundCount()).isZero();
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
        assertThat(s.endMarkerDayOffset()).isNull(); // no END ⇒ no offset
        assertThat(s.roundCount()).isEqualTo(3);
        assertThat(s.currentState()).isEqualTo(RoundState.OPEN);
    }

    @Test
    void singleMarkerTimeConfidenceIsZeroWithOnlyOneDatedPost() {
        ClusterPosts cluster = new ClusterPosts(List.of(post(0, "2026-01-05T09:00:00Z"), undated(1)));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(cluster));

        assertThat(s.single().timeOfDayUtc()).isEqualTo("09:00");
        assertThat(s.single().confidence()).isZero(); // < 2 dated posts (judgment point 2)
        assertThat(s.groupTypeConfidence()).isCloseTo(0.667, offset(0.01)); // min(1, 2/3)
        assertThat(s.roundCount()).isEqualTo(2);
    }

    @Test
    void twoMarkerContiguousRoundSpansTheWeekSoOpenDaysAreAllSeven() {
        // START (Sun 09:00) opens; END (the following Sat 18:00) closes ~6 days later; the next START opens ~15h after
        // that END (a near-contiguous group). The open round spans Sun→Sat ⇒ every weekday is open, including Sunday.
        ClusterPosts startCluster = new ClusterPosts(List.of(
                post(0, "2026-01-04T09:00:00Z"), post(2, "2026-01-11T09:00:00Z")));   // Sundays
        ClusterPosts endCluster = new ClusterPosts(List.of(
                post(1, "2026-01-10T18:00:00Z"), post(3, "2026-01-17T18:00:00Z")));   // Saturdays

        ScheduleFacet s = ScheduleDeriver.derive(List.of(startCluster, endCluster));

        assertThat(s.groupType()).isEqualTo(MarkerGroupType.TWO_MARKER);
        assertThat(s.start().timeOfDayUtc()).isEqualTo("09:00");
        assertThat(s.end().timeOfDayUtc()).isEqualTo("18:00");
        assertThat(s.openWeekdays()).containsExactly(0, 1, 2, 3, 4, 5, 6); // the round spans the whole week
        assertThat(s.openingDaysConfidence()).isEqualTo(1.0);              // every round covers the full span
        assertThat(s.endMarkerDayOffset()).isEqualTo(6);                   // Sun → the following Sat
        assertThat(s.roundCount()).isEqualTo(2);
        assertThat(s.currentState()).isEqualTo(RoundState.CLOSED_PERIOD);  // trailing marker is an END
    }

    @Test
    void twoMarkerSameDayRoundHasZeroOffsetAndPerDayOpenSpan() {
        // A round that opens 06:00 and closes 22:00 the SAME day (open 16h > closed 8h overnight) ⇒ offset 0.
        ClusterPosts startCluster = new ClusterPosts(List.of(
                post(0, "2026-01-05T06:00:00Z"), post(2, "2026-01-06T06:00:00Z")));   // Mon, Tue 06:00
        ClusterPosts endCluster = new ClusterPosts(List.of(
                post(1, "2026-01-05T22:00:00Z"), post(3, "2026-01-06T22:00:00Z")));   // Mon, Tue 22:00

        ScheduleFacet s = ScheduleDeriver.derive(List.of(startCluster, endCluster));

        assertThat(s.start().timeOfDayUtc()).isEqualTo("06:00");
        assertThat(s.end().timeOfDayUtc()).isEqualTo("22:00");
        assertThat(s.endMarkerDayOffset()).isZero();          // opens + closes the same calendar day
        assertThat(s.openWeekdays()).containsExactly(1, 2);   // Mon + Tue (each round is a single day)
        assertThat(s.roundCount()).isEqualTo(2);
        assertThat(s.currentState()).isEqualTo(RoundState.CLOSED_PERIOD);
    }

    @Test
    void twoMarkerLabelsTheLongerGapClusterAsStart() {
        // The FIRST cluster is the END (short gap to the next START); the SECOND is START (long open round) — the
        // gap-magnitude labelling picks the second as START (covers the else branch).
        ClusterPosts endFirst = new ClusterPosts(List.of(
                post(1, "2026-01-10T18:00:00Z"), post(3, "2026-01-17T18:00:00Z")));   // END (Sat)
        ClusterPosts startSecond = new ClusterPosts(List.of(
                post(0, "2026-01-04T09:00:00Z"), post(2, "2026-01-11T09:00:00Z")));   // START (Sun)

        ScheduleFacet s = ScheduleDeriver.derive(List.of(endFirst, startSecond));

        assertThat(s.start().timeOfDayUtc()).isEqualTo("09:00"); // the second cluster won START (longer following gap)
        assertThat(s.end().timeOfDayUtc()).isEqualTo("18:00");
        assertThat(s.endMarkerDayOffset()).isEqualTo(6);
    }

    @Test
    void twoMarkerRepeatedStartBeforeEndUsesTheLatestStart() {
        // Two STARTs occur before the first END ⇒ the open time moves to the later START (covers the repeated-open path).
        ClusterPosts startCluster = new ClusterPosts(List.of(
                post(0, "2026-01-05T09:00:00Z"), post(1, "2026-01-06T09:00:00Z")));   // Mon, Tue (both START)
        ClusterPosts endCluster = new ClusterPosts(List.of(post(2, "2026-01-08T09:00:00Z")));   // Thu (one END)

        ScheduleFacet s = ScheduleDeriver.derive(List.of(startCluster, endCluster));

        assertThat(s.endMarkerDayOffset()).isEqualTo(2); // the round opens at the LATER START (Tue) and closes Thu
        assertThat(s.roundCount()).isEqualTo(1);
    }

    @Test
    void twoMarkerTrailingStartMeansRoundOpen() {
        // Sequence ends on a START with no END after it → a round is currently open.
        ClusterPosts startCluster = new ClusterPosts(List.of(
                post(0, "2026-01-04T09:00:00Z"), post(2, "2026-01-11T09:00:00Z"), post(4, "2026-01-18T09:00:00Z")));
        ClusterPosts endCluster = new ClusterPosts(List.of(
                post(1, "2026-01-10T18:00:00Z"), post(3, "2026-01-17T18:00:00Z")));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(startCluster, endCluster));

        assertThat(s.currentState()).isEqualTo(RoundState.OPEN); // trailing post is the Sun 01-18 START
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
        assertThat(s.endMarkerDayOffset()).isNull(); // no reconstructable rounds
        assertThat(s.roundCount()).isZero();
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
        assertThat(s.endMarkerDayOffset()).isNull();
        assertThat(s.roundCount()).isZero();
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
    void openingDaysConfidenceDropsWhenRoundsCoverDifferentDays() {
        // Round 1 spans Mon→Wed; round 2 is a single Fri ⇒ the union is {Mon,Tue,Wed,Fri} but no round covers all of
        // it, so the consistency confidence is < 1.
        ClusterPosts startCluster = new ClusterPosts(List.of(
                post(0, "2026-01-05T06:00:00Z"), post(2, "2026-01-09T06:00:00Z")));   // Mon, Fri (starts)
        ClusterPosts endCluster = new ClusterPosts(List.of(
                post(1, "2026-01-07T22:00:00Z"), post(3, "2026-01-09T22:00:00Z")));   // Wed, Fri (ends)

        ScheduleFacet s = ScheduleDeriver.derive(List.of(startCluster, endCluster));

        assertThat(s.openWeekdays()).containsExactly(1, 2, 3, 5); // Mon,Tue,Wed (round 1) + Fri (round 2)
        assertThat(s.openingDaysConfidence()).isLessThan(1.0).isGreaterThan(0.0);
    }

    @Test
    void twoMarkerGroupTypeConfidenceComesFromSymmetryAndPairing() {
        // 3 START posts vs 1 END ⇒ asymmetric (symmetry = 1 - 2/3 = 0.33); the alternation is broken ⇒ low pairing.
        ClusterPosts startCluster = new ClusterPosts(List.of(
                post(0, "2026-01-05T09:00:00Z"), post(1, "2026-01-06T09:00:00Z"), post(2, "2026-01-07T09:00:00Z")));
        ClusterPosts endCluster = new ClusterPosts(List.of(post(3, "2026-01-08T17:00:00Z")));

        ScheduleFacet s = ScheduleDeriver.derive(List.of(startCluster, endCluster));

        assertThat(s.symmetry()).isCloseTo(0.333, offset(0.01)); // 1 - |3-1|/3
        assertThat(s.pairing()).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
        assertThat(s.groupTypeConfidence()).isCloseTo((s.symmetry() + s.pairing()) / 2, offset(0.001));
    }
}

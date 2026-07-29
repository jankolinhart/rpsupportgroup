package com.reelypops.rpsupportgroup.vetting;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit test for the circular (time-of-day) statistics behind the P1.5 timing merge: a tightly-timed set of posts yields
 * a signature at its circular-mean time-of-day; too few dated posts or a scattered spread yields none; the mean is
 * normalised into the day for afternoon times; and circular distance takes the short way round the 24-hour clock.
 */
class MarkerTimingTest {

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void tightMorningPostsYieldASignatureAtTheirMeanTimeOfDay() {
        MarkerTiming.TimeSignature sig = MarkerTiming.signature(List.of(
                at("2026-01-04T09:00:00Z"), at("2026-01-11T09:02:00Z"), at("2026-01-18T08:58:00Z")), 2, 0.85);

        assertThat(sig).isNotNull();
        assertThat(sig.meanMinutes()).isCloseTo(540.0, within(2.0)); // ~09:00
        assertThat(sig.concentration()).isGreaterThan(0.99);
    }

    @Test
    void afternoonMeanIsNormalisedIntoTheDay() {
        // 18:00 posts drive atan2 negative — the mean must wrap back into [0,1440).
        MarkerTiming.TimeSignature sig = MarkerTiming.signature(List.of(
                at("2026-01-04T18:00:00Z"), at("2026-01-11T18:00:00Z")), 2, 0.85);

        assertThat(sig).isNotNull();
        assertThat(sig.meanMinutes()).isCloseTo(1080.0, within(0.5)); // 18:00
    }

    @Test
    void tooFewDatedPostsYieldNoSignature() {
        assertThat(MarkerTiming.signature(List.of(at("2026-01-04T09:00:00Z")), 2, 0.85)).isNull();
    }

    @Test
    void scatteredTimesYieldNoSignature() {
        // 03:00 and 21:00 sit a quarter-day either side of noon — the resultant length collapses to ~0.71, below the bar.
        assertThat(MarkerTiming.signature(List.of(
                at("2026-01-04T03:00:00Z"), at("2026-01-11T21:00:00Z")), 2, 0.85)).isNull();
    }

    @Test
    void circularDistanceTakesTheShortWayRoundMidnight() {
        assertThat(MarkerTiming.circularDistanceMinutes(540, 570)).isEqualTo(30);   // 09:00 -> 09:30 direct
        assertThat(MarkerTiming.circularDistanceMinutes(1430, 10)).isEqualTo(20);   // 23:50 -> 00:10 wraps midnight
    }
}

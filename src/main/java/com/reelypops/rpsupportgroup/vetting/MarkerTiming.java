package com.reelypops.rpsupportgroup.vetting;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Circular (time-of-day) statistics for the P1.5 <strong>timing merge</strong> (vision §5.8 / plan M6). A re-screenshot
 * of a marker banner is visually identical but reframes/crops enough that its perceptual dHash can drift past the
 * clustering threshold and <em>split</em> the one marker into two clusters. Those split clusters still share the same
 * <strong>round-cadence time-of-day</strong> (a START posted ~09:00 every round, whoever posts it), so a matching
 * time-of-day signature is the content-independent signal that unifies them.
 *
 * <p>Time-of-day is <strong>circular</strong> (23:59 is one minute before 00:00), so the mean and spread are computed as
 * a resultant vector on the 24-hour circle, not an arithmetic average of minutes. {@link #signature} returns a
 * {@link TimeSignature} only when a cluster has enough dated posts AND they are tightly concentrated in time-of-day
 * (a real timed marker); a scattered or sparsely-dated cluster returns {@code null} and never participates in the merge.
 * All times are interpreted in <strong>UTC</strong> (tz-agnostic until the admin sets the group timezone).
 */
final class MarkerTiming {

    static final int MINUTES_PER_DAY = 24 * 60;

    private MarkerTiming() {
    }

    /** A cluster's round-cadence time-of-day signature: the circular-mean {@code meanMinutes} (0..1440, UTC) + how
     *  tightly the posts concentrate around it ({@code concentration}, the mean resultant length, 0..1; 1 = identical). */
    record TimeSignature(double meanMinutes, double concentration) {
    }

    /**
     * The time-of-day signature of a set of post instants, or {@code null} when it is not a usable timed marker: fewer
     * than {@code minCount} dated posts, or a concentration below {@code minConcentration} (times too scattered to be a
     * single round boundary). {@code meanMinutes} is the circular mean time-of-day in UTC minutes.
     */
    static TimeSignature signature(List<Instant> times, int minCount, double minConcentration) {
        if (times.size() < minCount) {
            return null;
        }
        double sumSin = 0.0;
        double sumCos = 0.0;
        for (Instant t : times) {
            double minutes = t.atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay() / 60.0;
            double angle = 2 * Math.PI * minutes / MINUTES_PER_DAY;
            sumSin += Math.sin(angle);
            sumCos += Math.cos(angle);
        }
        double concentration = Math.sqrt(sumSin * sumSin + sumCos * sumCos) / times.size();
        if (concentration < minConcentration) {
            return null;
        }
        double meanAngle = Math.atan2(sumSin, sumCos);
        double meanMinutes = meanAngle / (2 * Math.PI) * MINUTES_PER_DAY;
        if (meanMinutes < 0) {
            meanMinutes += MINUTES_PER_DAY;
        }
        return new TimeSignature(meanMinutes, concentration);
    }

    /** Shortest distance (minutes) between two times-of-day on the 24-hour circle (so 23:50 and 00:10 are 20 apart). */
    static double circularDistanceMinutes(double a, double b) {
        double d = Math.abs(a - b);
        return Math.min(d, MINUTES_PER_DAY - d);
    }
}

package com.reelypops.rpsupportgroup.group;

import java.util.List;
import java.util.Optional;

/**
 * The admin-confirmed <strong>per-weekday</strong> schedule (M4.5, vision §5c) — the authoritative weekly program the
 * Vetting Portal edits: one {@link DayDefinition} per weekday the admin configures (OPEN with its own round shape +
 * detector, or CLOSED). Carried on {@link VettedProfile#weeklySchedule()} (jsonb, additive — no migration). The legacy
 * flat {@link GroupDefinition} is a one-way projection of the first OPEN day (see
 * {@link VettedProfile#withDerivedDefinition()}); <strong>M5</strong> retires that projection and the client + scanner
 * read this per-day structure directly.
 *
 * @param days the configured weekday slices (need not be all 7 — an unlisted weekday is treated as closed)
 */
public record WeeklyScheduleDefinition(List<DayDefinition> days) {

    /** The representative day for the flat projection: the first OPEN day (M4.5-e), or empty when every day is closed. */
    Optional<DayDefinition> representativeDay() {
        return days == null ? Optional.empty() : days.stream().filter(DayDefinition::open).findFirst();
    }

    /** The open weekday indices (0=Sun … 6=Sat), sorted — the projected {@link GroupDefinition#openWeekdays()}. */
    List<Integer> openWeekdays() {
        return days == null ? List.of()
                : days.stream().filter(DayDefinition::open).map(DayDefinition::weekday).sorted().toList();
    }
}

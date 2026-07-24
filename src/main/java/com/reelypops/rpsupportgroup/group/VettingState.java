package com.reelypops.rpsupportgroup.group;

/**
 * The first-class vetting lifecycle of an SG config (P1). Supersedes the plain {@code vetted} boolean (Cycle 10),
 * which is kept as a stored <em>projection</em> of {@code == VETTED} so the existing {@code browseVetted} query and
 * the client's lock-on-vet logic stay untouched.
 *
 * <p>A client-requested or client-uploaded config starts {@link #UNDER_VERIFICATION}; a ReelyPops operator then moves
 * it to an outcome:
 * <ul>
 *   <li>{@link #VETTED} — approved, publicly browsable, the creator's authoritative fields lock;</li>
 *   <li>{@link #REJECTED} — a <em>soft, re-requestable-after-cooldown</em> decision (nonsense-lite / below the
 *       follower-quality threshold), carrying a reason + a cooldown;</li>
 *   <li>{@link #BLOCKED} — the terminal, admin-only abuse verdict (nonsense / spam).</li>
 * </ul>
 */
public enum VettingState {
    UNDER_VERIFICATION,
    VETTED,
    REJECTED,
    BLOCKED
}

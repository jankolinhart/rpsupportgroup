package com.reelypops.rpsupportgroup.group;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The single point that mutates a {@link SgMembership} (B6).
 *
 * <h2>Two kinds of caller, and they are not equal</h2>
 * <b>An explicit act beats an observation.</b> {@link #claim} and {@link #release} are things the USER did —
 * both arrive through rpserver, from the {@code sub} of a signed token, and {@link #claim} additionally passed
 * the {@code groups.max} gate. {@link #report} is a thing a client SAW: rpenduser replays the desktop's raw
 * {@code supportGroups[]} out of every M5.1 device report, and nothing about that array is authenticated intent.
 * So a release leaves a {@link SgMembershipTombstone} on the natural triple, {@link #report} will not create or
 * confirm a membership while that tombstone stands, and only {@link #claim} lifts it. Re-joining is unaffected —
 * the claim clears the mark and records the membership in the same transaction.
 *
 * <p>That asymmetry is the whole fix for a released membership coming back on its own. The report path used to
 * re-INSERT the row a release had just deleted, so the slot the user was given back was silently re-taken (and
 * released again on their next attempt), which reads from outside as intermittent breakage rather than as a rule.</p>
 *
 * <h2>The report rules, unchanged for everything else</h2>
 * Every WRITE element flows through {@link #report(UUID, String, String, String)}, which bakes in the
 * never-self-expire rules:
 *
 * <ul>
 *   <li>{@code "following"} — confirm FOLLOWING and stamp {@code followingConfirmedAt}; insert a new row
 *       (stamping {@code instantiatedAt}) when none exists.</li>
 *   <li>{@code "not_following"} — the ONLY flip to NOT_FOLLOWING; stamp {@code statusReportedAt}. An explicit
 *       negative for an unseen account inserts a NOT_FOLLOWING row (a definite, present signal — not array
 *       absence).</li>
 *   <li>anything else ({@code "unknown"}, {@code "requested"}, blank, {@code null}, or any non-exact token) —
 *       fail-open NO-OP: never touch a stored row's status, never insert.</li>
 * </ul>
 *
 * <p>The follow signal is matched against the EXACT lower-case tokens {@code "following"} / {@code "not_following"}
 * (trimmed, never case-folded), so a mixed-case or unexpected value degrades to the fail-open branch rather than
 * silently triggering a flip. There is deliberately no scheduled sweep, TTL or purge on this service — nothing but
 * an explicit report ever changes a stored status, and nothing but an explicit claim or release moves a tombstone.</p>
 *
 * <p><strong>Concurrency.</strong> The upsert reads the row, then inserts or updates. Two concurrent first-time
 * writes for the same {@code (userId, igHandle, igAccount)} can both read no row and both attempt an INSERT, so
 * one loses the {@code uq_sg_membership_natural} race with a {@link DataIntegrityViolationException}. Rather than
 * surface that as a 500, {@link #convergingOnTheInsertRace} catches it and runs the attempt once more in a fresh
 * transaction: the winner's row is now present, so the loser re-reads it and converges on an UPDATE. Each attempt
 * is its own physical transaction (a failed insert poisons its persistence context, so the retry cannot share it)
 * — hence {@link #attemptReport} and {@link #attemptClaim} are invoked through the {@link #self} proxy, never by
 * direct self-call.</p>
 */
@Service
public class SgMembershipService {

    private static final Logger log = LoggerFactory.getLogger(SgMembershipService.class);

    private static final String FOLLOWING = "following";
    private static final String NOT_FOLLOWING = "not_following";

    private final SgMembershipRepository memberships;
    private final SgMembershipReleaseRepository releases;
    private final SgMembershipTombstoneRepository tombstones;
    private final SgMembershipService self;

    public SgMembershipService(SgMembershipRepository memberships,
                               SgMembershipReleaseRepository releases,
                               SgMembershipTombstoneRepository tombstones,
                               @Lazy SgMembershipService self) {
        this.memberships = memberships;
        this.releases = releases;
        this.tombstones = tombstones;
        this.self = self;
    }

    /**
     * Apply one WRITE element idempotently. Inconclusive signals fail open here (no transaction is even opened);
     * the two definite signals are dispatched to {@link #attemptReport} in their own transaction, with a single
     * retry if a concurrent first-time write won the natural-key INSERT. Handles are normalised downstream.
     *
     * <p>This is the OBSERVATION path. It cannot revive a membership its user has explicitly released — see the
     * class note and {@link SgMembershipTombstone}.</p>
     */
    public void report(UUID userId, String igHandle, String igAccount, String followingStatus) {
        String signal = followingStatus == null ? "" : followingStatus.trim();
        if (!FOLLOWING.equals(signal) && !NOT_FOLLOWING.equals(signal)) {
            return; // unknown / requested / blank / null / anything else -> fail-open no-op (no read, no write).
        }
        convergingOnTheInsertRace(() -> self.attemptReport(userId, igHandle, igAccount, signal));
    }

    /**
     * CLAIM one membership: the user is (re-)joining a support group. The explicit act, and the ONLY thing that
     * lifts a {@link SgMembershipTombstone}.
     *
     * <p>rpserver reaches this after its {@code groups.max} gate has admitted the claim, with the user taken from
     * the token's {@code sub}. It is the mirror of {@link #release}, and the pairing is what makes the tombstone
     * safe: a user who gives a group back and later wants it again simply joins it again, and the membership is
     * restored exactly as it would have been the first time — same natural key, same FOLLOWING row, a fresh
     * {@code instantiatedAt} when the row had really gone.</p>
     *
     * <p>Deliberately NOT reachable from the report path, which shares {@link #attemptReport}'s upsert but not
     * its authority: if an observation could clear the mark, the mark would be worth nothing.</p>
     *
     * @return {@code true} when this claim lifted a standing release (the user re-joined something they had given
     *         back), {@code false} for an ordinary join
     */
    public boolean claim(UUID userId, String igHandle, String igAccount) {
        return convergingOnTheInsertRace(() -> self.attemptClaim(userId, igHandle, igAccount));
    }

    /**
     * One upsert attempt in its own transaction. NOT a caller entry point — always reached via {@link #report}
     * through the {@link #self} proxy so the retry gets a clean transaction. {@code signal} is already trimmed and
     * is exactly {@link #FOLLOWING} or {@link #NOT_FOLLOWING}.
     *
     * <p>The tombstone is read TWICE, and both reads matter. The first refuses an observation about a membership
     * the user has already given back. The second catches the case this bug was actually reported as: a report
     * that was <em>already in flight</em> when the removal happened, whose first read ran before the release
     * committed. Re-reading before returning means such a report is undone rather than left standing, so the
     * released slot does not come back seconds after the user freed it.</p>
     *
     * @return {@code true} when the row was written, {@code false} when a standing release outranked this report
     */
    @Transactional
    public boolean attemptReport(UUID userId, String igHandle, String igAccount, String signal) {
        String handle = normalize(igHandle);
        String account = normalize(igAccount);
        if (released(userId, handle, account)) {
            log.debug("Ignoring a '{}' report for a released membership (user={} handle={} group={}) — "
                    + "an explicit release outranks an observation until an explicit claim re-joins.",
                    signal, userId, handle, account);
            return false;
        }
        Instant now = Instant.now();
        SgMembership existing = memberships.findByUserIdAndIgHandleAndIgAccount(userId, handle, account).orElse(null);
        if (FOLLOWING.equals(signal)) {
            if (existing == null) {
                memberships.save(SgMembership.following(userId, handle, account, now));
            } else {
                existing.confirmFollowing(now);
            }
        } else {
            if (existing == null) {
                memberships.save(SgMembership.notFollowing(userId, handle, account, now));
            } else {
                existing.flipNotFollowing(now);
            }
        }
        if (released(userId, handle, account)) {
            // A release committed underneath this attempt. It is the newer, explicit act, so it wins: undo.
            memberships.findByUserIdAndIgHandleAndIgAccount(userId, handle, account).ifPresent(memberships::delete);
            log.info("A device report for @{} in @{} arrived across a release and was undone — "
                    + "the removal stands (user={}).", handle, account, userId);
            return false;
        }
        return true;
    }

    /**
     * One claim attempt in its own transaction. NOT a caller entry point — see {@link #claim}.
     *
     * <p>The mark is lifted FIRST, so the membership this method goes on to write can never be undone by its own
     * tombstone, and so a claim that races nothing still reads as one atomic act: the group is the user's again.</p>
     */
    @Transactional
    public boolean attemptClaim(UUID userId, String igHandle, String igAccount) {
        String handle = normalize(igHandle);
        String account = normalize(igAccount);
        boolean revived = tombstones.deleteByUserIdAndIgHandleAndIgAccount(userId, handle, account) > 0L;
        Instant now = Instant.now();
        SgMembership existing = memberships.findByUserIdAndIgHandleAndIgAccount(userId, handle, account)
                .orElse(null);
        if (existing == null) {
            memberships.save(SgMembership.following(userId, handle, account, now));
        } else {
            existing.confirmFollowing(now);
        }
        if (revived) {
            log.info("Membership re-claimed after a release: user={} handle={} group={} — the tombstone is lifted.",
                    userId, handle, account);
        }
        return revived;
    }

    /**
     * RELEASE one membership: the user is giving a support group back. Scoped to the registry's own natural key
     * {@code (userId, igHandle, igAccount)}, so the row for one handle in one group goes and every OTHER handle's
     * row — including this user's other handles in the SAME group, and this handle's rows in other groups —
     * is untouched.
     *
     * <p><strong>The row is DELETED, not flipped to NOT_FOLLOWING.</strong> Both would free the quota slot
     * ({@code MembershipQuotaService} counts FOLLOWING rows), but they say different things to the Q6 board ring,
     * which reads this same registry three-state: no row at all means the board does not exist for this caller
     * (404), whereas a NOT_FOLLOWING row means listed-but-withheld (403) and keeps the group in the user's
     * {@code /leaderboard/my-groups} with a "withheld" note. Someone who deliberately gave a group back must not
     * keep seeing it listed, so the row goes.
     *
     * <p><strong>And it STAYS gone.</strong> A {@link SgMembershipTombstone} is raised on the same triple before
     * the row is removed, and while it stands {@link #report} will not re-create the membership from a device
     * report. Without it the deletion was only ever advisory: the desktop's next {@code supportGroups[]} replay
     * put the row straight back and silently re-took the slot. Only {@link #claim} lifts the mark, so re-joining
     * is unchanged and everything else is refused.
     *
     * <p>The tombstone is raised whether or not a row was found. A release names an intent about a triple, not
     * about whichever row happened to exist at that instant — and a retry sent after an in-flight report had
     * already resurrected the membership must still leave the user released, not half-released.
     *
     * <p>This does NOT weaken the record's durability rule. Nothing here sweeps, ages out or purges anything —
     * {@link SgMembership} still has no TTL and no scheduled expiry. A release is an explicit, authenticated act
     * by the row's OWN user (rpserver takes the user from the JWT subject and never from a request body), and the
     * membership's passing is recorded in {@link SgMembershipRelease} before it goes.
     *
     * <p><strong>Idempotent by contract</strong> — releasing a membership that is already gone is a SUCCESS, not
     * a fault: the client retries this call, and a retry must not become an error the user is shown. Only a real
     * removal writes a trace, so retries cannot inflate the audit history.
     *
     * @return {@code true} when a row was actually removed (and a trace written), {@code false} when there was
     *         nothing to release
     */
    @Transactional
    public boolean release(UUID userId, String igHandle, String igAccount) {
        String handle = normalize(igHandle);
        String account = normalize(igAccount);
        Instant now = Instant.now();
        raiseTombstone(userId, handle, account, now);
        SgMembership existing = memberships.findByUserIdAndIgHandleAndIgAccount(userId, handle, account)
                .orElse(null);
        if (existing == null) {
            return false;   // already released (or never held): idempotent success, and no trace to write.
        }
        // The trace is taken BEFORE the delete: the row cannot record its own passing once it is gone.
        releases.save(SgMembershipRelease.of(userId, existing, now));
        memberships.delete(existing);
        return true;
    }

    /** Every membership row for a user (internal READ). */
    @Transactional(readOnly = true)
    public List<SgMembership> byUser(UUID userId) {
        return memberships.findByUserId(userId);
    }

    /** Is there a standing release on this triple — i.e. did the user give this group back and not re-join it? */
    private boolean released(UUID userId, String handle, String account) {
        return tombstones.findByUserIdAndIgHandleAndIgAccount(userId, handle, account).isPresent();
    }

    /** Raise or re-date the standing mark. One row per triple, so a second release re-dates rather than piles up. */
    private void raiseTombstone(UUID userId, String handle, String account, Instant now) {
        tombstones.findByUserIdAndIgHandleAndIgAccount(userId, handle, account)
                .ifPresentOrElse(existing -> existing.reReleased(now),
                        () -> tombstones.save(SgMembershipTombstone.of(userId, handle, account, now)));
    }

    /**
     * Run one attempt, and if it lost the {@code uq_sg_membership_natural} INSERT race to a concurrent first-time
     * write, run it exactly once more. The retry is a fresh transaction (see the class note), and by then the
     * winner's row is present, so the second pass re-reads it and converges on an UPDATE instead of colliding.
     */
    private boolean convergingOnTheInsertRace(BooleanSupplier attempt) {
        try {
            return attempt.getAsBoolean();
        } catch (DataIntegrityViolationException lostTheInsertRace) {
            return attempt.getAsBoolean();
        }
    }

    private static String normalize(String handle) {
        return handle.trim().toLowerCase(Locale.ROOT);
    }
}

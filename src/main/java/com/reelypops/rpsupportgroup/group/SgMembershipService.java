package com.reelypops.rpsupportgroup.group;

import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The single point that mutates a {@link SgMembership} (B6). Every WRITE element flows through
 * {@link #report(UUID, String, String, String)}, which bakes in the never-self-expire rules:
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
 * an explicit report ever changes a stored status.</p>
 *
 * <p><strong>Concurrency.</strong> The upsert reads the row, then inserts or updates. Two concurrent first-time
 * reports for the same {@code (userId, igHandle, igAccount)} can both read no row and both attempt an INSERT, so
 * one loses the {@code uq_sg_membership_natural} race with a {@link DataIntegrityViolationException}. Rather than
 * surface that as a 500, {@link #report} catches it and runs the attempt once more in a fresh transaction: the
 * winner's row is now present, so the loser re-reads it and converges on an UPDATE. Each attempt is its own
 * physical transaction (a failed insert poisons its persistence context, so the retry cannot share it) — hence
 * {@link #attemptReport} is invoked through the {@link #self} proxy, never by direct self-call.</p>
 */
@Service
public class SgMembershipService {

    private static final String FOLLOWING = "following";
    private static final String NOT_FOLLOWING = "not_following";

    private final SgMembershipRepository memberships;
    private final SgMembershipService self;

    public SgMembershipService(SgMembershipRepository memberships, @Lazy SgMembershipService self) {
        this.memberships = memberships;
        this.self = self;
    }

    /**
     * Apply one WRITE element idempotently. Inconclusive signals fail open here (no transaction is even opened);
     * the two definite signals are dispatched to {@link #attemptReport} in their own transaction, with a single
     * retry if a concurrent first-time report won the natural-key INSERT. Handles are normalised downstream.
     */
    public void report(UUID userId, String igHandle, String igAccount, String followingStatus) {
        String signal = followingStatus == null ? "" : followingStatus.trim();
        if (!FOLLOWING.equals(signal) && !NOT_FOLLOWING.equals(signal)) {
            return; // unknown / requested / blank / null / anything else -> fail-open no-op (no read, no write).
        }
        try {
            self.attemptReport(userId, igHandle, igAccount, signal);
        } catch (DataIntegrityViolationException lostTheInsertRace) {
            // A concurrent first-time report inserted the row under our natural key between our read and write.
            // Re-run once in a fresh transaction: the row is now present, so this attempt re-reads and UPDATEs it.
            self.attemptReport(userId, igHandle, igAccount, signal);
        }
    }

    /**
     * One upsert attempt in its own transaction. NOT a caller entry point — always reached via {@link #report}
     * through the {@link #self} proxy so the retry gets a clean transaction. {@code signal} is already trimmed and
     * is exactly {@link #FOLLOWING} or {@link #NOT_FOLLOWING}.
     */
    @Transactional
    public void attemptReport(UUID userId, String igHandle, String igAccount, String signal) {
        String handle = normalize(igHandle);
        String account = normalize(igAccount);
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
    }

    /** Every membership row for a user (internal READ). */
    @Transactional(readOnly = true)
    public List<SgMembership> byUser(UUID userId) {
        return memberships.findByUserId(userId);
    }

    private static String normalize(String handle) {
        return handle.trim().toLowerCase(Locale.ROOT);
    }
}

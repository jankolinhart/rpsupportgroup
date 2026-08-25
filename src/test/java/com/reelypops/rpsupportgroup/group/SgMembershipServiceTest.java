package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The B6 membership record's mutation rules, exercised against a real Postgres (Testcontainers) so the idempotent
 * upsert, the never-self-expire flip semantics and the case-insensitive natural key are proven end-to-end. Each test
 * uses a fresh random {@code userId} for isolation. The repository is a {@link SpyBean} (it delegates to the real
 * bean by default) so the natural-key INSERT race can be simulated deterministically.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SgMembershipServiceTest {

    @Autowired
    SgMembershipService service;

    @SpyBean
    SgMembershipRepository repository;

    @Autowired
    SgMembershipReleaseRepository releases;

    /** Spied (delegating to the real bean) so the in-flight-report seam can be reproduced deterministically. */
    @SpyBean
    SgMembershipTombstoneRepository tombstones;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private SgMembership only(UUID userId) {
        List<SgMembership> rows = service.byUser(userId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private SgMembership row(UUID userId, String handle, String account) {
        return repository.findByUserIdAndIgHandleAndIgAccount(userId, handle, account).orElseThrow();
    }

    @Test
    void followingCreatesFollowingRowAndStampsInstantiatedAndConfirmed() {
        UUID user = UUID.randomUUID();

        service.report(user, "OwnerHandle", "TargetAccount", "following");

        SgMembership row = only(user);
        assertThat(row.getStatus()).isEqualTo(SgMembershipStatus.FOLLOWING);
        assertThat(row.getFollowingConfirmedAt()).isNotNull();
        assertThat(row.getInstantiatedAt()).isNotNull();
        assertThat(row.getStatusReportedAt()).isNull();
        // handles are normalised (trimmed + lower-cased) for a case-insensitive key
        assertThat(row.getIgHandle()).isEqualTo("ownerhandle");
        assertThat(row.getIgAccount()).isEqualTo("targetaccount");
    }

    @Test
    void followingIsIdempotentAndNeverMovesInstantiatedAt() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "a", "following");
        var instantiated = only(user).getInstantiatedAt();

        service.report(user, "h", "a", "following");

        SgMembership row = only(user); // still exactly one row
        assertThat(row.getStatus()).isEqualTo(SgMembershipStatus.FOLLOWING);
        assertThat(row.getInstantiatedAt()).isEqualTo(instantiated);
    }

    @Test
    void notFollowingFlipsAnExistingFollowingRowAndStampsReportedAt() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "a", "following");
        var instantiated = only(user).getInstantiatedAt();

        service.report(user, "h", "a", "not_following");

        SgMembership row = only(user);
        assertThat(row.getStatus()).isEqualTo(SgMembershipStatus.NOT_FOLLOWING);
        assertThat(row.getStatusReportedAt()).isNotNull();
        assertThat(row.getInstantiatedAt()).isEqualTo(instantiated); // preserved across the flip
    }

    @Test
    void notFollowingForAnUnseenAccountInsertsANotFollowingRow() {
        UUID user = UUID.randomUUID();

        service.report(user, "h", "a", "not_following");

        SgMembership row = only(user);
        assertThat(row.getStatus()).isEqualTo(SgMembershipStatus.NOT_FOLLOWING);
        assertThat(row.getStatusReportedAt()).isNotNull();
        assertThat(row.getInstantiatedAt()).isNotNull();
        assertThat(row.getFollowingConfirmedAt()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"unknown", "requested", "", "   ", "NOT_FOLLOWING", "Following", "not-following"})
    void inconclusiveReportsPreserveAPriorFollowing(String signal) {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "a", "following");
        Instant confirmedAt = only(user).getFollowingConfirmedAt(); // capture BEFORE the no-op

        service.report(user, "h", "a", signal);

        SgMembership row = only(user);
        assertThat(row.getStatus()).isEqualTo(SgMembershipStatus.FOLLOWING);
        assertThat(row.getStatusReportedAt()).isNull();
        assertThat(row.getFollowingConfirmedAt()).isEqualTo(confirmedAt); // the no-op touched nothing
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"unknown", "requested", "", "   ", "FOLLOWING", "Following", "follow"})
    void inconclusiveReportsPreserveAPriorNotFollowing(String signal) {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "a", "not_following");
        Instant reportedAt = only(user).getStatusReportedAt(); // capture BEFORE the no-op

        service.report(user, "h", "a", signal);

        SgMembership row = only(user);
        assertThat(row.getStatus()).isEqualTo(SgMembershipStatus.NOT_FOLLOWING); // symmetric never-self-expire
        assertThat(row.getStatusReportedAt()).isEqualTo(reportedAt); // the no-op touched nothing
        assertThat(row.getFollowingConfirmedAt()).isNull();
    }

    @Test
    void aStoredNotFollowingSurvivesOmissionFromABatch() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "keep", "not_following"); // the NOT_FOLLOWING row that must be preserved
        Instant reportedAt = row(user, "h", "keep").getStatusReportedAt();

        // A later batch reports only OTHER accounts; "keep" is simply absent (absence != unfollow, absence != follow).
        service.report(user, "h", "other", "following");

        SgMembership kept = row(user, "h", "keep");
        assertThat(kept.getStatus()).isEqualTo(SgMembershipStatus.NOT_FOLLOWING); // untouched by the omission
        assertThat(kept.getStatusReportedAt()).isEqualTo(reportedAt); // and its timestamp is unchanged
        assertThat(service.byUser(user)).hasSize(2); // the other account was added alongside, not in place of
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"unknown", "requested", "", "   "})
    void inconclusiveReportsForAnUnseenAccountInsertNothing(String signal) {
        UUID user = UUID.randomUUID();

        service.report(user, "h", "a", signal);

        assertThat(service.byUser(user)).isEmpty();
    }

    @Test
    void theNaturalKeyIsCaseInsensitiveOnTheHandles() {
        UUID user = UUID.randomUUID();
        service.report(user, "MixedCase", "AccountX", "following");

        service.report(user, "mixedcase", "accountx", "not_following"); // same row despite the casing

        SgMembership row = only(user);
        assertThat(row.getStatus()).isEqualTo(SgMembershipStatus.NOT_FOLLOWING);
    }

    @Test
    void byUserReturnsEveryRowForTheUser() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "accountOne", "following");
        service.report(user, "h", "accountTwo", "following");

        assertThat(service.byUser(user)).hasSize(2);
        assertThat(repository.findByUserId(user)).hasSize(2);
    }

    // ── the RELEASE: the user giving a support group back ───────────────────────────────────────────────

    @Test
    void releaseRemovesExactlyTheOneRowForThatHandleInThatGroup() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp", "following");

        assertThat(service.release(user, "h", "grp")).isTrue();

        assertThat(service.byUser(user)).isEmpty();
        assertThat(repository.findByUserIdAndIgHandleAndIgAccount(user, "h", "grp")).isEmpty();
    }

    /**
     * The scoping that matters. A user runs several handles across several groups, and releasing one membership
     * must take the ONE row named by the natural key — not the handle's other groups, not the group's other
     * handles, and emphatically not another user's row in the same group.
     */
    @Test
    void releaseLeavesEveryOtherHandleAndGroupUntouched() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        service.report(user, "alpha", "grp", "following");    // the one being released
        service.report(user, "beta", "grp", "following");     // same group, the user's OTHER handle
        service.report(user, "alpha", "grpTwo", "following"); // same handle, another group
        service.report(other, "alpha", "grp", "following");   // another user entirely, same key otherwise

        assertThat(service.release(user, "alpha", "grp")).isTrue();

        assertThat(service.byUser(user))
                .extracting(SgMembership::getIgHandle, SgMembership::getIgAccount)
                .containsExactlyInAnyOrder(tuple("beta", "grp"), tuple("alpha", "grptwo"));
        assertThat(service.byUser(other)).hasSize(1);
        assertThat(row(other, "alpha", "grp").getStatus()).isEqualTo(SgMembershipStatus.FOLLOWING);
    }

    /**
     * Releasing twice is a SUCCESS, because the client retries this call and the state it asked for is the state
     * that holds. The second pass reports that it removed nothing — and writes no second trace, so a retry storm
     * cannot forge a history of releases that never happened.
     */
    @Test
    void releasingTwiceSucceedsAndLeavesExactlyOneTrace() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp", "following");

        assertThat(service.release(user, "h", "grp")).isTrue();
        assertThat(service.release(user, "h", "grp")).isFalse();
        assertThat(service.release(user, "h", "grp")).isFalse();

        assertThat(releases.findByUserIdAndIgHandleAndIgAccount(user, "h", "grp")).hasSize(1);
    }

    @Test
    void releasingSomethingNeverHeldRemovesNothingAndTracesNothing() {
        UUID user = UUID.randomUUID();

        assertThat(service.release(user, "h", "neverJoined")).isFalse();

        assertThat(releases.findByUserId(user)).isEmpty();
    }

    /** The release honours the same case-insensitive natural key the upsert stores against. */
    @Test
    void releaseMatchesTheNaturalKeyCaseInsensitively() {
        UUID user = UUID.randomUUID();
        service.report(user, "OwnerHandle", "TargetAccount", "following");

        assertThat(service.release(user, "  OWNERHANDLE ", "targetACCOUNT")).isTrue();

        assertThat(service.byUser(user)).isEmpty();
    }

    /**
     * The audit trace: a release changes what a plan is being consumed by, and the deleted row cannot record its
     * own passing — so what it was, and what state it was in, is captured before it goes.
     */
    @Test
    void releaseWritesTheDurableTraceOfWhatWentAndWhatStateItWasIn() {
        UUID user = UUID.randomUUID();
        service.report(user, "H", "Grp", "following");
        Instant instantiated = only(user).getInstantiatedAt();

        service.release(user, "H", "Grp");

        assertThat(releases.findByUserId(user)).singleElement().satisfies(trace -> {
            assertThat(trace.getUserId()).isEqualTo(user);
            assertThat(trace.getIgHandle()).isEqualTo("h");          // normalised, as stored
            assertThat(trace.getIgAccount()).isEqualTo("grp");
            assertThat(trace.getPriorStatus()).isEqualTo(SgMembershipStatus.FOLLOWING);
            assertThat(trace.getPriorInstantiatedAt()).isEqualTo(instantiated);
            assertThat(trace.getReleasedAt()).isNotNull();
        });
    }

    /** Releasing a row the user had already left is still a real removal, and is traced with the status it held. */
    @Test
    void releasingANotFollowingRowIsTracedWithThatPriorStatus() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp", "not_following");

        assertThat(service.release(user, "h", "grp")).isTrue();

        assertThat(releases.findByUserId(user)).singleElement()
                .extracting(SgMembershipRelease::getPriorStatus)
                .isEqualTo(SgMembershipStatus.NOT_FOLLOWING);
    }

    /**
     * Release / re-claim / release again is legitimate, and the trace is a HISTORY rather than a single fact.
     *
     * <p>The middle step is a {@code claim}, not a {@code report}. It always described itself as "re-claimed
     * through the quota gate" while actually calling the report path, which is precisely the conflation the
     * tombstone rule ended: the second release only has something to remove because an EXPLICIT act put the
     * membership back.</p>
     */
    @Test
    void aReClaimedMembershipCanBeReleasedAgainAndBothReleasesAreTraced() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp", "following");
        service.release(user, "h", "grp");
        service.claim(user, "h", "grp");                 // re-claimed through the quota gate

        assertThat(service.release(user, "h", "grp")).isTrue();

        assertThat(releases.findByUserIdAndIgHandleAndIgAccount(user, "h", "grp")).hasSize(2);
    }

    /**
     * <strong>AN EXPLICIT REMOVAL STICKS — a release is not undone by the next device report.</strong>
     *
     * <p>This test used to assert the opposite and was named {@code _knownGap}, because that is what the code
     * did: {@link SgMembershipService#report} is reached from rpenduser's membership forwarding, which replays
     * the desktop client's raw {@code supportGroups[]} out of every M5.1 device report, and a {@code following}
     * element re-INSERTED the row a release had just deleted. The slot the user was handed back was silently
     * re-taken, then handed back again on their next attempt — intermittent breakage, from the outside.</p>
     *
     * <p>The ruling: an explicit act beats an observation. The release tombstones the triple, the report path
     * will not resurrect it, and only a claim lifts the mark.</p>
     */
    @Test
    void aReleasedMembershipIsNotResurrectedByTheNextFollowingReport() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp", "following");
        service.release(user, "h", "grp");
        assertThat(service.byUser(user)).isEmpty();

        service.report(user, "h", "grp", "following"); // the client's next device report, forwarded verbatim

        assertThat(service.byUser(user))
                .as("a device report re-created a membership its own user had explicitly released")
                .isEmpty();
    }

    /**
     * The same rule for the negative signal: a released triple is not the registry's business at all any more,
     * so a {@code not_following} report must not resurrect it as a NOT_FOLLOWING row either. That row would put
     * the group back in the Q6 board ring as listed-but-withheld (403) — visible to a user who deliberately got
     * rid of it, which is the outcome the DELETE was chosen over a status flip to avoid.
     */
    @Test
    void aReleasedMembershipIsNotRecreatedByANotFollowingReportEither() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp", "following");
        service.release(user, "h", "grp");

        service.report(user, "h", "grp", "not_following");

        assertThat(service.byUser(user)).isEmpty();
    }

    /**
     * <strong>And re-joining still works, which is the half a tombstone could easily have broken.</strong>
     *
     * <p>The claim and the report share the same natural key and the same upsert, so it would be entirely
     * possible to block the resurrection and block the legitimate re-join with it — and nobody would notice
     * until a user tried to add back a group they had removed. The claim lifts the mark and writes the row in
     * one act: same key, FOLLOWING, and a fresh {@code instantiatedAt} because the row really had gone.</p>
     */
    @Test
    void anExplicitClaimAfterAReleaseGenuinelyRestoresTheMembership() {
        UUID user = UUID.randomUUID();
        service.report(user, "H", "Grp", "following");
        service.release(user, "H", "Grp");
        assertThat(service.byUser(user)).isEmpty();

        assertThat(service.claim(user, "H", "Grp")).as("the claim lifted a standing release").isTrue();

        SgMembership restored = only(user);
        assertThat(restored.getStatus()).isEqualTo(SgMembershipStatus.FOLLOWING);
        assertThat(restored.getIgHandle()).isEqualTo("h");      // normalised on both paths alike
        assertThat(restored.getIgAccount()).isEqualTo("grp");
        assertThat(restored.getInstantiatedAt()).isNotNull();
        assertThat(restored.getFollowingConfirmedAt()).isNotNull();
    }

    /** And once re-claimed, the ORDINARY report path works on it again — the mark is gone, not merely ignored. */
    @Test
    void afterAReClaimTheReportPathConfirmsTheMembershipNormallyAgain() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp", "following");
        service.release(user, "h", "grp");
        service.claim(user, "h", "grp");

        service.report(user, "h", "grp", "not_following");   // an ordinary observation, no longer outranked

        assertThat(only(user).getStatus()).isEqualTo(SgMembershipStatus.NOT_FOLLOWING);
    }

    /**
     * A claim on a membership the user still holds is idempotent — and this is a real path, not a defensive
     * branch: {@code MembershipQuotaService} ADMITS an already-member claim without consuming a slot (re-adopting
     * or re-syncing a group is not growth), so it arrives whenever a client re-adds a group it never lost. It
     * confirms rather than duplicates, and it does not move {@code instantiatedAt} — the user has held this
     * membership since they first took it.
     */
    @Test
    void claimingAMembershipTheUserStillHoldsConfirmsItRatherThanAddingASecondRow() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp", "not_following");   // a group they had left, but whose row is still here
        Instant instantiated = only(user).getInstantiatedAt();

        assertThat(service.claim(user, "h", "grp")).as("nothing was tombstoned, so nothing was lifted").isFalse();

        SgMembership row = only(user);                       // still exactly one row
        assertThat(row.getStatus()).isEqualTo(SgMembershipStatus.FOLLOWING);
        assertThat(row.getFollowingConfirmedAt()).isNotNull();
        assertThat(row.getInstantiatedAt()).isEqualTo(instantiated);
    }

    /** A claim for a group the user never released is an ordinary join and says so. */
    @Test
    void aFirstTimeClaimIsAnOrdinaryJoinAndLiftsNothing() {
        UUID user = UUID.randomUUID();

        assertThat(service.claim(user, "h", "grp")).isFalse();

        assertThat(only(user).getStatus()).isEqualTo(SgMembershipStatus.FOLLOWING);
    }

    /** Tombstones are per triple: releasing one group must not silence reports about a different one. */
    @Test
    void aReleaseOfOneGroupDoesNotBlockReportsAboutAnother() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp-a", "following");
        service.report(user, "h", "grp-b", "following");

        service.release(user, "h", "grp-a");
        service.report(user, "h", "grp-a", "following");   // refused
        service.report(user, "h", "grp-b", "following");   // untouched

        assertThat(service.byUser(user)).singleElement()
                .extracting(SgMembership::getIgAccount).isEqualTo("grp-b");
    }

    /**
     * <strong>The report that was ALREADY IN FLIGHT when the user removed the group.</strong>
     *
     * <p>This is the case the bug was actually reported as, and the one a single up-front check cannot catch: the
     * report reads the tombstone table BEFORE the release commits, sees nothing, and goes on to write. So the
     * attempt re-reads before it returns, and undoes itself if a release landed underneath it — the newer,
     * explicit act wins.</p>
     *
     * <p>Reproduced deterministically at that exact seam: the first tombstone lookup is stubbed to answer
     * "nothing" (the pre-release read), and everything afterwards — the real release's row, the re-read, the
     * undo — is genuine against Postgres.</p>
     */
    @Test
    void aReportAlreadyInFlightWhenTheReleaseLandsIsUndoneRatherThanLeftStanding() {
        UUID user = UUID.randomUUID();
        service.report(user, "h", "grp", "following");
        service.release(user, "h", "grp");   // the user removes the group; the tombstone is real from here on

        java.util.Optional<SgMembershipTombstone> standing =
                tombstones.findByUserIdAndIgHandleAndIgAccount(user, "h", "grp");
        assertThat(standing).as("the release must have raised a tombstone for this test to mean anything")
                .isPresent();

        // The in-flight report's FIRST look at the tombstone table happened before that release committed; every
        // look after it sees the mark that is really there.
        java.util.concurrent.atomic.AtomicInteger looks = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> looks.getAndIncrement() == 0 ? java.util.Optional.empty() : standing)
                .when(tombstones).findByUserIdAndIgHandleAndIgAccount(user, "h", "grp");

        service.report(user, "h", "grp", "following");

        assertThat(service.byUser(user))
                .as("a report that straddled the release left the membership standing")
                .isEmpty();
    }

    /**
     * A concurrent first-time report can win the natural-key INSERT between this report's read and its own write,
     * so the loser's INSERT collides with {@code uq_sg_membership_natural}. That must NOT surface as a 500:
     * {@code report} catches the {@link DataIntegrityViolationException} and re-runs once, and the retry re-reads
     * the now-present row and converges on it via an UPDATE.
     *
     * <p>The race is reproduced deterministically at the exact seam the task calls out — between the read and the
     * write. The first read (unstubbed) sees no row; the losing INSERT is then intercepted to (1) commit the
     * winner's row in an INDEPENDENT transaction so it survives this attempt's rollback and is visible to the
     * retry, and (2) raise the natural-key violation the DB would raise. The retry's read and UPDATE are entirely
     * real, so convergence is proven against Postgres, not mocked.</p>
     */
    @Test
    void convergesWhenAConcurrentFirstReportWonTheNaturalKeyInsert() {
        UUID user = UUID.randomUUID();

        doAnswer(invocation -> {
            commitWinnerRowInSeparateTransaction(user); // a concurrent report commits our key between read and write
            throw new DataIntegrityViolationException("simulated uq_sg_membership_natural violation");
        }).when(repository).save(any(SgMembership.class));

        // Must converge rather than 500 on the natural-key collision.
        service.report(user, "h", "a", "following");

        SgMembership converged = only(user); // exactly one row -> no duplicate, no violation surfaced
        assertThat(converged.getStatus()).isEqualTo(SgMembershipStatus.FOLLOWING); // the retry applied the UPDATE
        assertThat(converged.getFollowingConfirmedAt()).isNotNull();
        // Only the losing INSERT called save; the retry took the UPDATE (dirty-check) path, so save fired exactly once.
        verify(repository, times(1)).save(any(SgMembership.class));
    }

    /** Commit a NOT_FOLLOWING winner row for {@code (user,"h","a")} in its own tx, decoupled from any caller's tx. */
    private void commitWinnerRowInSeparateTransaction(UUID user) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> jdbc.update(
                "INSERT INTO sg_membership "
                        + "(id, user_id, ig_handle, ig_account, status, instantiated_at, status_reported_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'h', 'a', 'NOT_FOLLOWING', now(), now(), now(), now())",
                UUID.randomUUID(), user));
    }
}

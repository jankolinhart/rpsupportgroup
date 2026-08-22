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

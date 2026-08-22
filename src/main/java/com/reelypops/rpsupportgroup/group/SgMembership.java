package com.reelypops.rpsupportgroup.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * The AUTHORITATIVE record of one user's follow relationship to one of a support group's Instagram accounts (B6).
 * rpenduser forwards each client's observed follow status here (east-west, {@code ROLE_INTERNAL}) and reads it
 * back to decide suppression. Keyed by {@code (userId, igHandle, igAccount)} — the handles are stored lower-cased
 * so the natural key is case-insensitive.
 *
 * <p><strong>The record is deliberate and durable.</strong> Status is mutated by exactly one path
 * ({@link SgMembershipService#report}); nothing self-expires it — no TTL, no {@code @Scheduled} sweep, no purge.
 * A positive {@code following} report confirms {@link SgMembershipStatus#FOLLOWING} and stamps
 * {@link #followingConfirmedAt} (whose age is display-only, never a trigger); an explicit {@code not_following}
 * report is the ONLY flip to {@link SgMembershipStatus#NOT_FOLLOWING} and stamps {@link #statusReportedAt}; an
 * inconclusive report ({@code unknown}/{@code requested}/blank) never touches a stored row. {@link #instantiatedAt}
 * is stamped once, when the row is first created, and never moved.</p>
 */
@Entity
@Table(name = "sg_membership")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SgMembership {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Getter
    @Column(name = "ig_handle", nullable = false, updatable = false)
    private String igHandle;

    @Getter
    @Column(name = "ig_account", nullable = false, updatable = false)
    private String igAccount;

    @Getter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SgMembershipStatus status;

    /** When a positive {@code following} report last confirmed the follow — display-only; its age never triggers. */
    @Getter
    @Column(name = "following_confirmed_at")
    private Instant followingConfirmedAt;

    /** When this membership row was first created; stamped once and never moved (survives a later status flip). */
    @Getter
    @Column(name = "instantiated_at")
    private Instant instantiatedAt;

    /** When an explicit {@code not_following} report last set {@link SgMembershipStatus#NOT_FOLLOWING}. */
    @Getter
    @Column(name = "status_reported_at")
    private Instant statusReportedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private SgMembership(UUID userId, String igHandle, String igAccount, SgMembershipStatus status, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.igHandle = igHandle;
        this.igAccount = igAccount;
        this.status = status;
        this.instantiatedAt = now;
    }

    /** A new row from a positive {@code following} report: FOLLOWING, with both instantiated + confirmed stamped. */
    static SgMembership following(UUID userId, String igHandle, String igAccount, Instant now) {
        SgMembership m = new SgMembership(userId, igHandle, igAccount, SgMembershipStatus.FOLLOWING, now);
        m.followingConfirmedAt = now;
        return m;
    }

    /** A new row from an explicit {@code not_following} report: NOT_FOLLOWING, with instantiated + reported stamped. */
    static SgMembership notFollowing(UUID userId, String igHandle, String igAccount, Instant now) {
        SgMembership m = new SgMembership(userId, igHandle, igAccount, SgMembershipStatus.NOT_FOLLOWING, now);
        m.statusReportedAt = now;
        return m;
    }

    /** Positive {@code following} on an existing row: (re)confirm FOLLOWING; refresh confirmed-at; leave instantiated. */
    void confirmFollowing(Instant now) {
        this.status = SgMembershipStatus.FOLLOWING;
        this.followingConfirmedAt = now;
    }

    /** Explicit {@code not_following} on an existing row: the only flip to NOT_FOLLOWING; stamp reported-at. */
    void flipNotFollowing(Instant now) {
        this.status = SgMembershipStatus.NOT_FOLLOWING;
        this.statusReportedAt = now;
    }
}

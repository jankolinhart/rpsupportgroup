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

import java.time.Instant;
import java.util.UUID;

/**
 * The durable TRACE of one {@link SgMembership} being given back by its own user (B6 release).
 *
 * <p>A release changes what a plan is being consumed by — {@code MembershipQuotaService} enforces
 * {@code groups.max} against the FOLLOWING rows of {@code sg_membership}, so removing one hands the user a slot
 * back. That is exactly the kind of act that must not happen invisibly, and the deleted row cannot record its own
 * deletion, so the trace lives in its own table: who released, which handle in which group, when, and what state
 * the membership was in when it went.
 *
 * <p><strong>Written only when a row was really removed.</strong> The release route is idempotent because the
 * desktop client retries it, and a repeat release of something already gone succeeds while writing nothing here.
 * Otherwise a retry storm would forge a history of releases that never happened, and a history that inflates
 * under retry is not a history. There is no unique key on the natural triple either: release / re-claim / release
 * again is a legitimate sequence, and each pass is its own entry.
 *
 * <p>Nothing ever mutates a row of this table, and nothing sweeps it — like {@link SgMembership} it has no TTL,
 * no {@code @Scheduled} purge and no expiry.
 */
@Entity
@Table(name = "sg_membership_release")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SgMembershipRelease {

    @Id
    private UUID id;

    @Getter
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Getter
    @Column(name = "ig_handle", nullable = false, updatable = false)
    private String igHandle;

    @Getter
    @Column(name = "ig_account", nullable = false, updatable = false)
    private String igAccount;

    /** When the membership was released — the moment the slot came back. */
    @Getter
    @Column(name = "released_at", nullable = false, updatable = false)
    private Instant releasedAt;

    /** The status the membership held when it went; a FOLLOWING release is the one that frees a quota slot. */
    @Getter
    @Enumerated(EnumType.STRING)
    @Column(name = "prior_status", nullable = false, updatable = false)
    private SgMembershipStatus priorStatus;

    /** The released membership's {@code instantiatedAt} — how long the user had actually held it. */
    @Getter
    @Column(name = "prior_instantiated_at", updatable = false)
    private Instant priorInstantiatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The trace of {@code released} going, taken before it is deleted (its fields are unreadable afterwards). */
    static SgMembershipRelease of(UUID userId, SgMembership released, Instant now) {
        SgMembershipRelease trace = new SgMembershipRelease();
        trace.id = UUID.randomUUID();
        trace.userId = userId;
        trace.igHandle = released.getIgHandle();
        trace.igAccount = released.getIgAccount();
        trace.releasedAt = now;
        trace.priorStatus = released.getStatus();
        trace.priorInstantiatedAt = released.getInstantiatedAt();
        return trace;
    }
}

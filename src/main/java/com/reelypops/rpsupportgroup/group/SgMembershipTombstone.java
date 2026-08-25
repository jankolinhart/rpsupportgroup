package com.reelypops.rpsupportgroup.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * The standing mark that a user has RELEASED {@code (userId, igHandle, igAccount)} and has not since re-joined it.
 *
 * <h2>An explicit act beats an observation</h2>
 * A release is something the user DID. A device report is something a client SAW. Before this row existed the
 * observation won: {@link SgMembershipService#report} is reached from rpenduser's membership forwarding, which
 * replays the desktop's raw {@code supportGroups[]} out of every M5.1 report, so a {@code following} element
 * re-INSERTED the membership a release had just deleted — the freed slot came back, and then went again on the
 * next removal, which is indistinguishable from the feature being broken. While this row stands the report path
 * will neither create nor confirm that membership; only an explicit {@link SgMembershipService#claim} — the
 * mirror act, and the one rpserver's {@code groups.max} gate sits in front of — removes it.
 *
 * <h2>Current state, not history</h2>
 * One row per natural triple ({@code uq_sg_membership_tombstone_natural}), created by a release and destroyed by
 * a claim. That is deliberately NOT how {@link SgMembershipRelease} works: the release trace is an append-only
 * history in which release / re-claim / release again is three entries, and deriving "is this membership
 * currently released?" from it would mean comparing the newest release against the newest claim — which requires
 * recording claims for no other purpose, and gives the wrong answer outright when two rows share a timestamp. A
 * row that either exists or does not cannot be misread.
 *
 * <p>Like every other B6 table this one has no TTL, no {@code @Scheduled} purge and no sweep: it is written and
 * removed only by explicit, authenticated acts of its own user.</p>
 */
@Entity
@Table(name = "sg_membership_tombstone")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SgMembershipTombstone {

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

    /** When the release that raised this tombstone happened; refreshed if the user releases again. */
    @Getter
    @Column(name = "released_at", nullable = false)
    private Instant releasedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    static SgMembershipTombstone of(UUID userId, String igHandle, String igAccount, Instant now) {
        SgMembershipTombstone tombstone = new SgMembershipTombstone();
        tombstone.id = UUID.randomUUID();
        tombstone.userId = userId;
        tombstone.igHandle = igHandle;
        tombstone.igAccount = igAccount;
        tombstone.releasedAt = now;
        return tombstone;
    }

    /** A second release of a membership the user had re-claimed: the same standing mark, re-dated. */
    void reReleased(Instant now) {
        this.releasedAt = now;
    }
}

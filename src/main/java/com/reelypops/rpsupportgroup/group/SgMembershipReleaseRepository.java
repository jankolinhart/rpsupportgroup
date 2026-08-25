package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** The append-only trace of memberships their own user gave back (B6 release). */
public interface SgMembershipReleaseRepository extends JpaRepository<SgMembershipRelease, UUID> {

    /** Every release this user has made, for the audit read. */
    List<SgMembershipRelease> findByUserId(UUID userId);

    /** Every release of one (user, handle, group) — a list, not an Optional: releasing twice over is legitimate. */
    List<SgMembershipRelease> findByUserIdAndIgHandleAndIgAccount(UUID userId, String igHandle, String igAccount);
}

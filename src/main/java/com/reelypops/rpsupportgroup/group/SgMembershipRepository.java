package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The per-user support-group membership record (B6). */
public interface SgMembershipRepository extends JpaRepository<SgMembership, UUID> {

    /** The one row for a user + IG account pair (natural key), used to drive the idempotent upsert. Handles are
     *  stored lower-cased, so callers pass normalised values for a case-insensitive match. */
    Optional<SgMembership> findByUserIdAndIgHandleAndIgAccount(UUID userId, String igHandle, String igAccount);

    /** Every membership row for a user — the internal read surface. */
    List<SgMembership> findByUserId(UUID userId);
}

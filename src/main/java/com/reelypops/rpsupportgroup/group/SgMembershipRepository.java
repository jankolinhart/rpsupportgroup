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

    /**
     * Every membership row FOR ONE GROUP — the registry read in the other direction.
     *
     * <p>Everything here has been user-first: given a customer, what do they hold. An operator asking to
     * deep-scrape a group needs the inverse — given a group, WHO holds it — because a scrape duty has to be
     * sent to a machine that actually runs it, and a duty sent anywhere else is silently ignored by the
     * client that receives it.</p>
     *
     * <p>Handles and accounts are stored lower-cased, so the caller passes a normalised value.</p>
     */
    List<SgMembership> findByIgAccount(String igAccount);
}

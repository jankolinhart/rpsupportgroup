package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Standing releases: one row per {@code (userId, igHandle, igAccount)} the user has given back and not re-taken. */
public interface SgMembershipTombstoneRepository extends JpaRepository<SgMembershipTombstone, UUID> {

    Optional<SgMembershipTombstone> findByUserIdAndIgHandleAndIgAccount(UUID userId, String igHandle,
                                                                        String igAccount);

    /** Lift the mark on a re-join. Returns how many rows went, so the claim can say whether it revived anything. */
    long deleteByUserIdAndIgHandleAndIgAccount(UUID userId, String igHandle, String igAccount);
}

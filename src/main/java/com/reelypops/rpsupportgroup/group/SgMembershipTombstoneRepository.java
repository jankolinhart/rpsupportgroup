package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Standing releases: one row per {@code (userId, igHandle, igAccount)} the user has given back and not re-taken. */
public interface SgMembershipTombstoneRepository extends JpaRepository<SgMembershipTombstone, UUID> {

    Optional<SgMembershipTombstone> findByUserIdAndIgHandleAndIgAccount(UUID userId, String igHandle,
                                                                        String igAccount);

    /** Lift the mark on a re-join. Returns how many rows went, so the claim can say whether it revived anything. */
    long deleteByUserIdAndIgHandleAndIgAccount(UUID userId, String igHandle, String igAccount);

    /**
     * Every standing release for one user — what a CLIENT needs to catch up on.
     *
     * <p>A client that has been away learns what it must undo from these rows and from nothing else. It
     * cannot be told by absence: a membership missing from the registry might have been released, and might
     * equally be one this service has never been told about. Only a tombstone is a record of the user
     * actually giving something back.
     */
    List<SgMembershipTombstone> findByUserId(UUID userId);
}

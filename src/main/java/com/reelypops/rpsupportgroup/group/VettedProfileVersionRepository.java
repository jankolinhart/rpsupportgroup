package com.reelypops.rpsupportgroup.group;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only history of confirmed {@link VettedProfile} snapshots per config (M5 A2). */
public interface VettedProfileVersionRepository extends JpaRepository<VettedProfileVersion, UUID> {

    /** A config's snapshots, newest first — powers the admin history list and the next {@code snapshot_version}. */
    List<VettedProfileVersion> findByConfigIdOrderBySnapshotVersionDesc(UUID configId);

    /** The snapshot to reactivate on a rollback. */
    Optional<VettedProfileVersion> findByConfigIdAndSnapshotVersion(UUID configId, long snapshotVersion);
}

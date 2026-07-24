package com.reelypops.rpsupportgroup.corpus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CorpusSnapshotItemRepository extends JpaRepository<CorpusSnapshotItem, UUID> {

    /** A snapshot's items in grid order (ordinal ascending — newest tag on top). */
    List<CorpusSnapshotItem> findBySnapshotIdOrderByOrdinalAsc(UUID snapshotId);
}

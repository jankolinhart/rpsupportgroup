package com.reelypops.rpsupportgroup.corpus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MarkerCorpusSnapshotRepository extends JpaRepository<MarkerCorpusSnapshot, UUID> {

    /** A group's snapshots, newest pass first (the admin vetting-evidence list). */
    List<MarkerCorpusSnapshot> findByIgAccountOrderByCreatedAtDesc(String igAccount);

    /** Snapshots in a given state opened before a cutoff — the GC sweeper's orphaned-OPEN query. */
    List<MarkerCorpusSnapshot> findByStatusAndCreatedAtBefore(SnapshotStatus status, Instant cutoff);
}

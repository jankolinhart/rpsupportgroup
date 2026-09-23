package com.reelypops.rpsupportgroup.corpus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MarkerCorpusSnapshotRepository extends JpaRepository<MarkerCorpusSnapshot, UUID> {

    /** A group's snapshots, newest pass first (the admin vetting-evidence list). */
    List<MarkerCorpusSnapshot> findByIgAccountOrderByCreatedAtDesc(String igAccount);

    /** Snapshots in a given state opened before a cutoff — the GC sweeper's orphaned-OPEN query. */
    List<MarkerCorpusSnapshot> findByStatusAndCreatedAtBefore(SnapshotStatus status, Instant cutoff);

    /**
     * OPEN passes that have gone quiet — nothing appended since {@code cutoff}.
     *
     * <p>COALESCED with the open time, so a pass that never received a single item is caught by the same rule
     * instead of needing a second one. Null {@code lastItemAt} means "nothing has ever been written", which is
     * a real state — a scrape that died before its first page — not a missing value to default away.</p>
     */
    @Query("select s from MarkerCorpusSnapshot s where s.status = :status "
            + "and coalesce(s.lastItemAt, s.createdAt) < :cutoff")
    List<MarkerCorpusSnapshot> findSilentSince(@Param("status") SnapshotStatus status,
                                               @Param("cutoff") Instant cutoff);
}

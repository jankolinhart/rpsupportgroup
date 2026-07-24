package com.reelypops.rpsupportgroup.corpus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MarkerCorpusSnapshotRepository extends JpaRepository<MarkerCorpusSnapshot, UUID> {

    /** A group's snapshots, newest pass first (the admin vetting-evidence list). */
    List<MarkerCorpusSnapshot> findByIgAccountOrderByCreatedAtDesc(String igAccount);
}

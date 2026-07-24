package com.reelypops.rpsupportgroup.corpus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CorpusRepresentativeRepository extends JpaRepository<CorpusRepresentative, UUID> {

    Optional<CorpusRepresentative> findBySnapshotIdAndShortcode(UUID snapshotId, String shortcode);

    /** The shortcodes that carry a representative thumbnail for a snapshot (no image bytes loaded). */
    @Query("select r.shortcode from CorpusRepresentative r where r.snapshotId = :snapshotId order by r.shortcode")
    List<String> findShortcodesBySnapshotId(@Param("snapshotId") UUID snapshotId);
}

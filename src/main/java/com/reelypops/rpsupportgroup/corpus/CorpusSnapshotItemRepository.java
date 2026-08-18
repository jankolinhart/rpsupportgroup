package com.reelypops.rpsupportgroup.corpus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CorpusSnapshotItemRepository extends JpaRepository<CorpusSnapshotItem, UUID> {

    /** A snapshot's items in grid order (ordinal ascending — newest tag on top). */
    List<CorpusSnapshotItem> findBySnapshotIdOrderByOrdinalAsc(UUID snapshotId);

    /**
     * Every usable item a group's clients streamed for one post, newest pass first.
     *
     * <p>⚠️ The filters are the point, not incidental. A {@code REJECTED} snapshot streamed at least one malformed
     * fingerprint and is voided whole; an {@code unusable} item carries one itself. Either would hand back a value
     * of the right shape and the wrong meaning — which is the exact fault this query exists to repair.</p>
     */
    @Query("""
            select i from CorpusSnapshotItem i, MarkerCorpusSnapshot s
            where i.snapshotId = s.id
              and s.igAccount = :igAccount
              and s.status <> com.reelypops.rpsupportgroup.corpus.SnapshotStatus.REJECTED
              and i.shortcode = :shortcode
              and i.unusable = false
            order by s.createdAt desc
            """)
    List<CorpusSnapshotItem> findUsableByGroupAndShortcode(@Param("igAccount") String igAccount,
                                                           @Param("shortcode") String shortcode);

    /** Every item in a group's snapshots whose stored fingerprint is a given value (used to burn corrupt rows). */
    @Query("""
            select i from CorpusSnapshotItem i, MarkerCorpusSnapshot s
            where i.snapshotId = s.id
              and s.igAccount = :igAccount
              and i.dHash = :dHash
            """)
    List<CorpusSnapshotItem> findByGroupAndDHash(@Param("igAccount") String igAccount,
                                                  @Param("dHash") String dHash);
}

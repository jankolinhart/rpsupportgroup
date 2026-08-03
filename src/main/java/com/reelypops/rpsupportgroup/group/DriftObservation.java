package com.reelypops.rpsupportgroup.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One reporter's running LEDGER of a single drift for a config (M5 re-vet consumer). Upserted per
 * (config, kind, reporter [+ nominated handle]): the first report inserts the row (occurrence_count = 1); each later
 * report for the same natural key bumps occurrence_count + last_seen_at and refreshes the latest tally. An unresolved
 * {@link DriftKind#MARKER_DISAGREE} observation DERIVES the config's "needs re-vet" flag (a read-side signal — no
 * config mutation); a {@link DriftKind#NEW_OWNER} observation is an admin review-candidate nomination.
 */
@Entity
@Table(name = "drift_observation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriftObservation {

    @Id
    private UUID id;

    @Column(name = "config_id", nullable = false, updatable = false)
    private UUID configId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private DriftKind kind;

    @Column(name = "reporter_device_id", nullable = false, updatable = false, length = 128)
    private String reporterDeviceId;

    @Column(name = "reporter_user_id", updatable = false)
    private UUID reporterUserId;

    @Column(name = "nominated_owner_handle", updatable = false, length = 128)
    private String nominatedOwnerHandle;

    @Column(name = "agree_pass")
    private Integer agreePass;

    @Column(name = "disagree_pass")
    private Integer disagreePass;

    @Column(name = "persistence_count")
    private Integer persistenceCount;

    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    private DriftObservation(UUID configId, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                             String nominatedOwnerHandle, Integer agreePass, Integer disagreePass,
                             Integer persistenceCount, Instant now) {
        this.id = UUID.randomUUID();
        this.configId = configId;
        this.kind = kind;
        this.reporterDeviceId = reporterDeviceId;
        this.reporterUserId = reporterUserId;
        this.nominatedOwnerHandle = nominatedOwnerHandle;
        this.agreePass = agreePass;
        this.disagreePass = disagreePass;
        this.persistenceCount = persistenceCount;
        this.occurrenceCount = 1L;
        this.resolved = false;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    /** The first report from a reporter for this (config, kind [, handle]) — occurrence 1. */
    static DriftObservation first(UUID configId, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                                  String nominatedOwnerHandle, Integer agreePass, Integer disagreePass,
                                  Integer persistenceCount, Instant now) {
        return new DriftObservation(configId, kind, reporterDeviceId, reporterUserId, nominatedOwnerHandle,
                agreePass, disagreePass, persistenceCount, now);
    }

    /**
     * A later report for the same natural key: bump the occurrence count, refresh the latest tally, re-open a resolved
     * row (a fresh drift after a re-vet re-raises the flag), and advance last-seen.
     */
    void observeAgain(Integer agreePass, Integer disagreePass, Integer persistenceCount, Instant now) {
        this.occurrenceCount += 1L;
        this.agreePass = agreePass;
        this.disagreePass = disagreePass;
        this.persistenceCount = persistenceCount;
        this.resolved = false;
        this.lastSeenAt = now;
    }

    /** Mark this observation resolved (a re-vet cleared the derived marker-disagree flag). */
    void resolve() {
        this.resolved = true;
    }
}

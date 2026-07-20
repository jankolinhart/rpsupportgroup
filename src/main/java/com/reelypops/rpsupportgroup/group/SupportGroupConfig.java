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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A support-group config (Phase 1, F1): the authoritative SG {@link GroupDefinition} (jsonb) that both the
 * ReelyPops client (for liking) and the scanner (for analytics) read, keyed on the group's Instagram account.
 * A config is {@link ConfigStatus#UNCLAIMED} until an owner claims it (§6); {@code version} is the ETag the
 * client polls for changes (Q1).
 */
@Entity
@Table(name = "support_group_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportGroupConfig {

    @Id
    private UUID id;

    @Column(name = "ig_account", nullable = false, updatable = false)
    private String igAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ConfigStatus status;

    @Column(name = "owner_id")
    private UUID ownerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
    private GroupDefinition definition;

    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private SupportGroupConfig(String igAccount, GroupDefinition definition) {
        this.id = UUID.randomUUID();
        this.igAccount = igAccount;
        this.definition = definition;
        this.status = ConfigStatus.UNCLAIMED;
        this.version = 1L;
    }

    /** Create a new, unclaimed config for a support group (§6: auto-registered by the first client). */
    public static SupportGroupConfig createUnclaimed(String igAccount, GroupDefinition definition) {
        return new SupportGroupConfig(igAccount, definition);
    }
}

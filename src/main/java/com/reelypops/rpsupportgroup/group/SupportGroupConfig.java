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
import java.util.ArrayList;
import java.util.List;
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

    @Column(name = "admin_attributed", nullable = false)
    private boolean adminAttributed;

    @Column(name = "vetted", nullable = false)
    private boolean vetted;

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

    /** An owner claims this config, making it authoritative (§6). Bumps the version. */
    public void claim(UUID ownerId) {
        this.ownerId = ownerId;
        this.status = ConfigStatus.CLAIMED;
        this.version++;
    }

    /**
     * Admin override (Cycle 9): attribute this config to an owner WITHOUT the claim (verify + subscribe) flow —
     * for comping access + operator / E2E testing. Flags it {@code adminAttributed} (distinguishable, revocable).
     */
    public void attribute(UUID ownerId) {
        this.ownerId = ownerId;
        this.status = ConfigStatus.CLAIMED;
        this.adminAttributed = true;
        this.version++;
    }

    /**
     * Operator vetting (Cycle 10): mark this config as sanity-checked and publicly browsable. Idempotent — bumps the
     * version only on the false→true transition so polling clients notice and lock their authoritative fields.
     */
    public boolean vet() {
        if (vetted) {
            return false;
        }
        this.vetted = true;
        this.version++;
        return true;
    }

    /**
     * Register a discovered marker owner (Q4): idempotent — returns {@code false} and changes nothing if the
     * handle is already known, else appends it and bumps the version. First writer wins; later duplicates no-op.
     */
    public boolean addMarkerOwner(String handle) {
        List<String> current = owners(definition);
        if (current.contains(handle)) {
            return false;
        }
        List<String> updated = new ArrayList<>(current);
        updated.add(handle);
        this.definition = definition.withMarkerOwners(List.copyOf(updated));
        this.version++;
        return true;
    }

    /** Owner revokes a marker owner (Q4 safety-net): idempotent — {@code false} if it was not present. */
    public boolean removeMarkerOwner(String handle) {
        List<String> current = owners(definition);
        if (!current.contains(handle)) {
            return false;
        }
        List<String> updated = new ArrayList<>(current);
        updated.remove(handle);
        this.definition = definition.withMarkerOwners(List.copyOf(updated));
        this.version++;
        return true;
    }

    private static List<String> owners(GroupDefinition definition) {
        return definition.markerOwners() == null ? List.of() : definition.markerOwners();
    }
}

package com.reelypops.rpsupportgroup.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * The authoritative vetting lifecycle (P1). {@link #vetted} above is a stored <em>projection</em> of
     * {@code vettingState == VETTED}, maintained by the transitions below so the existing browse / lock logic is
     * untouched.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "vetting_state", nullable = false)
    private VettingState vettingState;

    /** Soft-reject reason shown to the requester (P1) — set only in {@link VettingState#REJECTED}. */
    @Column(name = "reject_reason")
    private String rejectReason;

    /** Earliest a soft-rejected group may be re-requested (P1) — set only in {@link VettingState#REJECTED}. */
    @Column(name = "cooldown_until")
    private Instant cooldownUntil;

    /** When the soft reject was recorded (P1). */
    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", columnDefinition = "jsonb")
    private GroupDefinition definition;

    /**
     * The admin-only vetting <strong>advisory</strong> (M3a) — the {@link DetectedProfile} the pipeline detected from the
     * corpus, which pre-fills the Vetting Portal. Not round truth (it never ships and never bumps {@code version}); it is
     * regenerated on every (re-)vet.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detected_profile", columnDefinition = "jsonb")
    private DetectedProfile detectedProfile;

    /**
     * A free-text group blurb (Cycle 11, R-1) shown lazily in the client's "i" info card. Authoritative (an admin
     * curates it; editable while PENDING, locked once vetted) but kept out of the jsonb {@code definition} because it
     * is not round truth, so it never bumps the definition {@code version} ETag the client polls.
     */
    @Column(name = "description")
    private String description;

    /**
     * Content categories (Cycle 12) — the admin-curated taxonomy this group is classified under. Metadata only
     * (never round truth), so changing it does NOT bump {@code version} (which is the definition ETag the client
     * polls). Eager because a config is always rendered with its category slugs (browse tiles + get).
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sg_config_category",
            joinColumns = @JoinColumn(name = "config_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<SgCategory> categories = new LinkedHashSet<>();

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
        this.vettingState = VettingState.UNDER_VERIFICATION;
        this.version = 1L;
    }

    /** Create a new, unclaimed config with an optional group description (Cycle 11, R-1). */
    public static SupportGroupConfig createUnclaimed(String igAccount, GroupDefinition definition, String description) {
        SupportGroupConfig c = new SupportGroupConfig(igAccount, definition);
        c.description = description;
        return c;
    }

    /**
     * Create a name-only <em>requested</em> config (P1): no definition yet (the admin/AI fills it during vetting), so it
     * lands UNDER_VERIFICATION with a null definition and cannot be vetted until a definition is set.
     */
    public static SupportGroupConfig createRequested(String igAccount) {
        return new SupportGroupConfig(igAccount, null);
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
     * Operator vetting (Cycle 10 / P1): approve → {@link VettingState#VETTED} (publicly browsable; the client locks its
     * authoritative fields). Idempotent — bumps the version only on the transition into VETTED so polling clients
     * notice. Keeps {@link #vetted} as the stored projection and clears any prior soft-reject detail. A
     * {@link VettingState#BLOCKED} config may not be vetted — the service guards that.
     */
    public boolean vet() {
        if (vettingState == VettingState.VETTED) {
            return false;
        }
        this.vettingState = VettingState.VETTED;
        this.vetted = true;
        this.rejectReason = null;
        this.cooldownUntil = null;
        this.rejectedAt = null;
        this.version++;
        return true;
    }

    /**
     * Operator soft-reject (P1): a <em>re-requestable-after-cooldown</em> decision carrying a {@code reason} + a
     * {@code cooldownUntil}. Clears the vetted projection and bumps the version. The service guards that a
     * {@link VettingState#BLOCKED} config cannot be soft-rejected.
     */
    public void reject(String reason, Instant cooldownUntil) {
        this.vettingState = VettingState.REJECTED;
        this.rejectReason = reason;
        this.cooldownUntil = cooldownUntil;
        this.rejectedAt = Instant.now();
        this.vetted = false;
        this.version++;
    }

    /**
     * Operator block (P1): the terminal, admin-only abuse verdict. Idempotent — bumps the version only on the
     * transition into BLOCKED. Clears the vetted projection + any soft-reject detail.
     */
    public boolean block() {
        if (vettingState == VettingState.BLOCKED) {
            return false;
        }
        this.vettingState = VettingState.BLOCKED;
        this.vetted = false;
        this.rejectReason = null;
        this.cooldownUntil = null;
        this.rejectedAt = null;
        this.version++;
        return true;
    }

    /** Operator correction (Cycle 10): replace the authoritative definition (fix nonsense timings etc.); bumps version. */
    public void updateDefinition(GroupDefinition definition) {
        this.definition = definition.canonicalized();
        this.version++;
    }

    /**
     * Store/refresh the admin-only vetting advisory (M3a). Not round truth, so it does NOT bump the client-facing
     * {@code version} ETag — the detected profile never ships; it only pre-fills the admin Vetting Portal.
     */
    public void updateDetectedProfile(DetectedProfile detectedProfile) {
        this.detectedProfile = detectedProfile;
    }

    /**
     * Operator correction (Cycle 11, R-1): set the authoritative group description. Not round truth, so it does not
     * bump the definition {@code version} the client polls — the "i" card fetches it lazily.
     */
    public void updateDescription(String description) {
        this.description = description;
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

    /**
     * Assign a content category (Cycle 12) — idempotent: returns {@code false} and changes nothing if the group is
     * already in that category. Metadata only, so it does not bump {@code version}.
     */
    public boolean assignCategory(SgCategory category) {
        if (categories.stream().anyMatch(c -> c.getSlug().equals(category.getSlug()))) {
            return false;
        }
        return categories.add(category);
    }

    /** Unassign a content category by slug (Cycle 12) — idempotent: {@code false} if it was not assigned. */
    public boolean unassignCategory(String slug) {
        return categories.removeIf(c -> c.getSlug().equals(slug));
    }
}

package com.reelypops.rpsupportgroup.group;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SG config registry (Phase 1, F1). A config is auto-registered {@code UNCLAIMED} by the first client to
 * configure a group (§6); it is keyed on the group's Instagram account, so a second registration for the same
 * account is a conflict. The authoritative definition is served to both the client (liking) and the scanner
 * (analytics) — the single shared contract.
 */
@Service
public class SupportGroupConfigService {

    private final SupportGroupConfigRepository configs;
    private final VettedProfileVersionRepository versions;
    private final DriftObservationRepository driftObservations;

    public SupportGroupConfigService(SupportGroupConfigRepository configs, VettedProfileVersionRepository versions,
                                     DriftObservationRepository driftObservations) {
        this.configs = configs;
        this.versions = versions;
        this.driftObservations = driftObservations;
    }

    /**
     * Idempotent request/create intake (P1), keyed on {@code igAccount}. If a config already exists (any state) it is
     * returned as-is (adopt-instead-of-duplicate, {@code created=false}); otherwise a new one is created
     * ({@code created=true}) — a name-only request (no definition) lands UNDER_VERIFICATION with a null definition, a
     * full upload lands as an UNCLAIMED config.
     */
    @Transactional
    public IntakeResult intake(String igAccount, GroupDefinition definition, String description) {
        return configs.findByIgAccount(igAccount)
                .map(existing -> new IntakeResult(existing, false))
                .orElseGet(() -> {
                    SupportGroupConfig created = definition == null
                            ? SupportGroupConfig.createRequested(igAccount)
                            : SupportGroupConfig.createUnclaimed(igAccount, definition.canonicalized(), description);
                    return new IntakeResult(configs.save(created), true);
                });
    }

    /** The outcome of {@link #intake}: the config + whether it was newly {@code created} (201) vs already existed (200). */
    public record IntakeResult(SupportGroupConfig config, boolean created) {
    }

    @Transactional(readOnly = true)
    public SupportGroupConfig get(String igAccount) {
        return require(igAccount);
    }

    /**
     * The structured field-level change-note (M5.3b {@code CONFIG_CHANGED}) of the config's currently-active vetted
     * snapshot — what changed vs the prior active snapshot when it was vetted. Empty when there is no active snapshot
     * or it is the first (a first-ready config is announced as {@code SG_VETTED}, not a diff; vision §5.16).
     */
    @Transactional(readOnly = true)
    public List<VettedProfileVersion.ChangeNoteEntry> activeChangeNote(SupportGroupConfig c) {
        Long active = c.getActiveSnapshotVersion();
        if (active == null) {
            return List.of();
        }
        return versions.findByConfigIdAndSnapshotVersion(c.getId(), active)
                .map(VettedProfileVersion::getChangeNote)
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public List<SupportGroupConfig> list() {
        return configs.findAllByOrderByCreatedAtDesc();
    }

    /**
     * The public browse list (Cycle 10/12): page the VETTED registry, optionally filtered by <em>any</em> of the
     * given category slugs (OR) and a case-insensitive substring on the group's IG account. Blank category slugs are
     * ignored; when none remain the category filter is skipped.
     */
    @Transactional(readOnly = true)
    public Page<SupportGroupConfig> browse(List<String> categories, String q, int page, int size) {
        List<String> slugs = categories == null ? List.of()
                : categories.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toLowerCase())
                        .toList();
        boolean noCats = slugs.isEmpty();
        Collection<String> slugParam = noCats ? List.of("") : slugs;
        String query = (q == null || q.isBlank()) ? "" : q.trim();
        return configs.browseVetted(noCats, slugParam, query, PageRequest.of(page, size));
    }

    /**
     * Admin override (Cycle 9): attribute an unclaimed config to a ReelyPops user WITHOUT the claim (verify +
     * subscribe) flow — for comping access + operator / E2E testing. Flags it {@code adminAttributed}.
     */
    @Transactional
    public SupportGroupConfig attribute(String igAccount, UUID ownerId) {
        SupportGroupConfig c = require(igAccount);
        c.attribute(ownerId);
        return configs.save(c);
    }

    /** Admin: remove a config entirely. */
    @Transactional
    public void remove(String igAccount) {
        configs.delete(require(igAccount));
    }

    /** Operator vetting (Cycle 10): approve a config → publicly browsable + locks the creator's authoritative fields. */
    @Transactional
    public SupportGroupConfig vet(String igAccount) {
        SupportGroupConfig c = require(igAccount);
        if (c.getVettingState() == VettingState.BLOCKED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, igAccount + " is blocked and cannot be vetted");
        }
        if (c.getDefinition() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, igAccount + " has no definition yet and cannot be vetted");
        }
        c.vet();
        SupportGroupConfig saved = configs.save(c);
        resolveMarkerDisagreeObservations(saved.getId());
        return saved;
    }

    /**
     * Operator soft-reject (P1): a re-requestable-after-cooldown decision carrying a reason + a cooldown of
     * {@code cooldownDays}. A BLOCKED config cannot be soft-rejected (terminal).
     */
    @Transactional
    public SupportGroupConfig reject(String igAccount, String reason, int cooldownDays) {
        SupportGroupConfig c = require(igAccount);
        if (c.getVettingState() == VettingState.BLOCKED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, igAccount + " is blocked and cannot be rejected");
        }
        c.reject(reason, Instant.now().plus(cooldownDays, ChronoUnit.DAYS));
        return configs.save(c);
    }

    /** Operator block (P1): the terminal, admin-only abuse verdict. Idempotent. */
    @Transactional
    public SupportGroupConfig block(String igAccount) {
        SupportGroupConfig c = require(igAccount);
        c.block();
        return configs.save(c);
    }

    /**
     * Operator correction (Cycle 11, R-1): replace a config's authoritative definition <em>and</em> its group
     * description in one call. The definition change bumps the {@code version} ETag; the description does not.
     */
    @Transactional
    public SupportGroupConfig updateConfig(String igAccount, GroupDefinition definition, String description) {
        SupportGroupConfig c = require(igAccount);
        c.updateDefinition(definition);
        c.updateDescription(description);
        return configs.save(c);
    }

    /**
     * Vetting Portal "Save" (M3a): persist the single authoritative {@link VettedProfile} and project its round-truth
     * one-way onto the legacy definition/description (§6). The config stays as-is (a pre-vetting correction — nothing
     * ships until Vet Now).
     */
    @Transactional
    public SupportGroupConfig saveVettedProfile(String igAccount, VettedProfile profile) {
        SupportGroupConfig c = require(igAccount);
        applyVettedProfile(c, profile);
        return configs.save(c);
    }

    /**
     * Vetting Portal "Vet Now" (M3a): save the {@link VettedProfile} (projecting the definition) <em>and</em> flip
     * UNDER_VERIFICATION → VETTED in one step. A BLOCKED config cannot be vetted.
     */
    @Transactional
    public SupportGroupConfig vetVettedProfile(String igAccount, VettedProfile profile) {
        SupportGroupConfig c = require(igAccount);
        applyVettedProfile(c, profile);
        if (c.getVettingState() == VettingState.BLOCKED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, igAccount + " is blocked and cannot be vetted");
        }
        c.vet();
        SupportGroupConfig saved = configs.save(c);
        resolveMarkerDisagreeObservations(saved.getId());
        return saved;
    }

    /**
     * Apply a Vetting-Portal save (M5 A2): append an immutable {@link VettedProfileVersion} history snapshot (carrying
     * the structured field-level change-note vs the prior active snapshot) and repoint the config's active snapshot. The
     * history append + the config write commit together in the caller's transaction.
     */
    private void applyVettedProfile(SupportGroupConfig c, VettedProfile profile) {
        long next = nextSnapshotVersion(c.getId());
        VettedProfile before = c.getVettedProfile();
        VettedProfile applied = c.saveVettedProfile(profile, next);
        // First snapshot has no prior version to diff → empty change-note (first-ready is announced via SG_VETTED, not
        // a CONFIG_CHANGED diff; vision §5.16). Every later save carries the structured field-level diff vs the prior
        // active snapshot, which the client renders into an i18n announcement (#5.3).
        List<VettedProfileVersion.ChangeNoteEntry> changeNote =
                before == null ? List.of() : VettedProfileDiff.diff(before, applied);
        versions.save(VettedProfileVersion.snapshot(c.getId(), next, applied, changeNote));
    }

    private long nextSnapshotVersion(UUID configId) {
        return versions.findByConfigIdOrderBySnapshotVersionDesc(configId).stream()
                .findFirst().map(v -> v.getSnapshotVersion() + 1).orElse(1L);
    }

    /** The config's active-snapshot pointer + its append-only history, newest first (M5 A2 admin history view). */
    @Transactional(readOnly = true)
    public GroupVersions listVersions(String igAccount) {
        SupportGroupConfig c = require(igAccount);
        return new GroupVersions(c.getActiveSnapshotVersion(),
                versions.findByConfigIdOrderBySnapshotVersionDesc(c.getId()));
    }

    /** A config's active-snapshot pointer + its full vetted-profile history (M5 A2). */
    public record GroupVersions(Long activeSnapshotVersion, List<VettedProfileVersion> versions) {
    }

    /**
     * Roll back / activate a prior version (M5 A2 kill switch): repoint the active snapshot to {@code snapshotVersion}
     * (restoring its profile + projection + bumping the ETag). 404 if that snapshot does not exist for the config.
     */
    @Transactional
    public SupportGroupConfig rollbackTo(String igAccount, long snapshotVersion) {
        SupportGroupConfig c = require(igAccount);
        VettedProfileVersion v = versions.findByConfigIdAndSnapshotVersion(c.getId(), snapshotVersion)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no snapshot " + snapshotVersion + " for " + igAccount));
        c.rollbackTo(snapshotVersion, v.getVettedProfile());
        return configs.save(c);
    }

    /** Flip the operational mode (M5 A2 kill switch): liking / scrape-only / paused. */
    @Transactional
    public SupportGroupConfig setMode(String igAccount, SgConfigMode mode) {
        SupportGroupConfig c = require(igAccount);
        c.setMode(mode);
        return configs.save(c);
    }

    /** The admin-only detected + vetted profile pair the Vetting Portal renders + diffs (M3a). */
    @Transactional(readOnly = true)
    public VettingProfilesResponse getVettingProfiles(String igAccount) {
        return VettingProfilesResponse.of(require(igAccount));
    }

    /** An owner claims an unclaimed config (§6). Idempotent guard: re-claiming a claimed config is a conflict. */
    @Transactional
    public SupportGroupConfig claim(String igAccount, UUID ownerId) {
        SupportGroupConfig c = require(igAccount);
        if (c.getStatus() == ConfigStatus.CLAIMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, igAccount + " is already claimed");
        }
        c.claim(ownerId);
        return configs.save(c);
    }

    /** Register a client-discovered marker owner (Q4). Any authenticated client may report; the add is idempotent. */
    @Transactional
    public SupportGroupConfig addMarkerOwner(String igAccount, String handle) {
        SupportGroupConfig c = require(igAccount);
        c.addMarkerOwner(handle);
        return configs.save(c);
    }

    /** Owner revokes a marker owner (Q4 safety-net) — only the config's owner may do so. */
    @Transactional
    public SupportGroupConfig removeMarkerOwner(String igAccount, String handle, UUID caller) {
        SupportGroupConfig c = require(igAccount);
        if (c.getOwnerId() == null || !c.getOwnerId().equals(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the owner may revoke a marker owner");
        }
        c.removeMarkerOwner(handle);
        return configs.save(c);
    }

    /**
     * Ingest one client-reported drift observation (M5 re-vet consumer): upserted per reporter. The first report from a
     * reporter for this (config, kind [, nominated handle]) inserts a row; a later report bumps its occurrence count and
     * refreshes the latest tally. An unresolved {@link DriftKind#MARKER_DISAGREE} observation derives the config's
     * "needs re-vet" flag, so this never mutates the config or bumps its ETag (a pure read-side signal). A
     * {@link DriftKind#NEW_OWNER} drift requires the nominated handle; a marker-disagree drift ignores it.
     */
    @Transactional
    public DriftObservation recordDrift(String igAccount, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                                        String nominatedOwnerHandle, Integer agreePass, Integer disagreePass,
                                        Integer persistenceCount) {
        if (kind == DriftKind.NEW_OWNER && (nominatedOwnerHandle == null || nominatedOwnerHandle.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "nominatedOwnerHandle is required for a NEW_OWNER drift");
        }
        SupportGroupConfig c = require(igAccount);
        String handle = kind == DriftKind.NEW_OWNER ? nominatedOwnerHandle : null;
        Instant now = Instant.now();
        Optional<DriftObservation> existing = handle == null
                ? driftObservations.findByConfigIdAndKindAndReporterDeviceIdAndNominatedOwnerHandleIsNull(
                        c.getId(), kind, reporterDeviceId)
                : driftObservations.findByConfigIdAndKindAndReporterDeviceIdAndNominatedOwnerHandle(
                        c.getId(), kind, reporterDeviceId, handle);
        DriftObservation obs = existing
                .map(o -> {
                    o.observeAgain(agreePass, disagreePass, persistenceCount, now);
                    return o;
                })
                .orElseGet(() -> DriftObservation.first(c.getId(), kind, reporterDeviceId, reporterUserId, handle,
                        agreePass, disagreePass, persistenceCount, now));
        return driftObservations.save(obs);
    }

    /** Resolve a config's open marker-disagree observations (a re-vet clears the derived "needs re-vet" flag). */
    private void resolveMarkerDisagreeObservations(UUID configId) {
        List<DriftObservation> open = driftObservations
                .findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(configId, DriftKind.MARKER_DISAGREE);
        open.forEach(DriftObservation::resolve);
        driftObservations.saveAll(open);
    }

    /** The config's derived re-vet status (M5): unresolved marker-disagree observations flag it + aggregate the reason. */
    @Transactional(readOnly = true)
    public RevetStatus revetStatus(SupportGroupConfig c) {
        return revetStatusOf(driftObservations
                .findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(c.getId(), DriftKind.MARKER_DISAGREE));
    }

    /**
     * The re-vet status of every given config (M5 admin list): one query fetches all their unresolved marker-disagree
     * observations, grouped in memory, so the list endpoint never issues a per-row query. Every config id maps to a
     * status (a config with no open observations maps to {@link RevetStatus#none()}).
     */
    @Transactional(readOnly = true)
    public Map<UUID, RevetStatus> revetStatuses(Collection<SupportGroupConfig> cs) {
        List<UUID> ids = cs.stream().map(SupportGroupConfig::getId).toList();
        Map<UUID, List<DriftObservation>> byConfig = ids.isEmpty()
                ? Map.of()
                : driftObservations.findByConfigIdInAndKindAndResolvedFalse(ids, DriftKind.MARKER_DISAGREE).stream()
                        .collect(Collectors.groupingBy(DriftObservation::getConfigId));
        Map<UUID, RevetStatus> out = new HashMap<>();
        for (SupportGroupConfig c : cs) {
            out.put(c.getId(), revetStatusOf(byConfig.getOrDefault(c.getId(), List.of())));
        }
        return out;
    }

    /** Aggregate a config's open marker-disagree observations into a re-vet status ({@code none} when there are none). */
    private RevetStatus revetStatusOf(List<DriftObservation> open) {
        if (open.isEmpty()) {
            return RevetStatus.none();
        }
        DriftObservation latest = open.stream().max(Comparator.comparing(DriftObservation::getLastSeenAt)).orElseThrow();
        long totalOccurrences = open.stream().mapToLong(DriftObservation::getOccurrenceCount).sum();
        int distinctReporters = (int) open.stream().map(DriftObservation::getReporterDeviceId).distinct().count();
        Integer maxPersistence = open.stream()
                .map(DriftObservation::getPersistenceCount).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(null);
        Instant firstSeen = open.stream().map(DriftObservation::getFirstSeenAt).min(Comparator.naturalOrder()).orElseThrow();
        RevetReason reason = new RevetReason(distinctReporters, totalOccurrences, latest.getAgreePass(),
                latest.getDisagreePass(), maxPersistence, firstSeen, latest.getLastSeenAt());
        return new RevetStatus(true, reason);
    }

    /** A config's open new-owner nominations, newest-seen first (M5 admin review-candidate surface). */
    @Transactional(readOnly = true)
    public List<DriftObservation> newOwnerNominations(String igAccount) {
        SupportGroupConfig c = require(igAccount);
        return driftObservations.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(c.getId(), DriftKind.NEW_OWNER);
    }

    /** A config's derived re-vet status (M5): whether it needs re-vetting + the aggregated reason ({@code null} if not). */
    public record RevetStatus(boolean needsRevet, RevetReason reason) {
        static RevetStatus none() {
            return new RevetStatus(false, null);
        }
    }

    private SupportGroupConfig require(String igAccount) {
        return configs.findByIgAccount(igAccount)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no config for " + igAccount));
    }
}

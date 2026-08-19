package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.corpus.MarkerCorpusService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final MarkerImageEnricher markerImageEnricher;
    private final MarkerImageStore markerImageStore;
    private final ClientMarkerImageRepository clientMarkerImages;
    private final MarkerCorpusService corpus;

    public SupportGroupConfigService(SupportGroupConfigRepository configs, VettedProfileVersionRepository versions,
                                     DriftObservationRepository driftObservations,
                                     MarkerImageEnricher markerImageEnricher,
                                     MarkerImageStore markerImageStore,
                                     ClientMarkerImageRepository clientMarkerImages,
                                     MarkerCorpusService corpus) {
        this.configs = configs;
        this.versions = versions;
        this.driftObservations = driftObservations;
        this.markerImageEnricher = markerImageEnricher;
        this.markerImageStore = markerImageStore;
        this.clientMarkerImages = clientMarkerImages;
        this.corpus = corpus;
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
        applyVettedProfile(igAccount, c, profile);
        return configs.save(c);
    }

    /**
     * Vetting Portal "Vet Now" (M3a): save the {@link VettedProfile} (projecting the definition) <em>and</em> flip
     * UNDER_VERIFICATION → VETTED in one step. A BLOCKED config cannot be vetted.
     */
    @Transactional
    public SupportGroupConfig vetVettedProfile(String igAccount, VettedProfile profile) {
        SupportGroupConfig c = require(igAccount);
        applyVettedProfile(igAccount, c, profile);
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
    private void applyVettedProfile(String igAccount, SupportGroupConfig c, VettedProfile profile) {
        rejectMalformedReferenceHashes(igAccount, profile);
        VettedProfile enriched = markerImageEnricher.enrich(igAccount, profile);
        long next = nextSnapshotVersion(c.getId());
        VettedProfile before = c.getVettedProfile();
        VettedProfile applied = c.saveVettedProfile(enriched, next);
        // First snapshot has no prior version to diff → empty change-note (first-ready is announced via SG_VETTED, not
        // a CONFIG_CHANGED diff; vision §5.16). Every later save carries the structured field-level diff vs the prior
        // active snapshot, which the client renders into an i18n announcement (#5.3).
        List<VettedProfileVersion.ChangeNoteEntry> changeNote =
                before == null ? List.of() : VettedProfileDiff.diff(before, applied);
        versions.save(VettedProfileVersion.snapshot(c.getId(), next, applied, changeNote));
    }

    /**
     * A usable perceptual hash: EXACTLY 64 binary digits, the length the client's {@code dhash.gradientHash}
     * produces and the only thing its matcher can compare.
     */
    private static final Pattern WELL_FORMED_DHASH = Pattern.compile("^[01]{64}$");

    /**
     * Refuse a vetted profile carrying a reference hash that is not a hash. THE PRODUCER BOUNDARY — nothing
     * malformed may enter the authoritative record, whatever wrote it.
     *
     * <p><strong>Why this exists.</strong> On 15/08/2026 `glowbloggeragency`'s Sunday START reference was stored
     * with a 39-character Instagram post SHORTCODE where its dHash belongs — byte-identical to the record's own
     * {@code shortcode} field, written by a portal fallback that substituted the post id when an advisory marker
     * could not be tied to an image cluster. Every comparison site in the client skips a hash whose length
     * differs from the candidate's, so the reference became INVISIBLE: it could not match, could not contradict,
     * and did not take part in the threshold calibration that sets the profile's width. It went unnoticed until a
     * misread marker cost a full day of likes.</p>
     *
     * <p>The portal itself no longer produces one (rpadminfrontend #95), so this is the backstop for anything that
     * reaches the API another way. It fails LOUDLY and names the offending value: a corrupt hash must never enter
     * a vetted profile, and it must never sit in one unnoticed. An EMPTY hash list is perfectly legal — a
     * text-only reference is honest and matchable by OCR; only a value pretending to be a hash is rejected.</p>
     */
    private void rejectMalformedReferenceHashes(String igAccount, VettedProfile profile) {
        if (profile == null) {
            return;
        }
        List<String> faults = new ArrayList<>();
        Stream.concat(
                        profile.detector() == null || profile.detector().references() == null
                                ? Stream.<VettedProfile.TypedMarkerReference>empty()
                                : profile.detector().references().stream(),
                        profile.weeklySchedule() == null || profile.weeklySchedule().days() == null
                                ? Stream.<VettedProfile.TypedMarkerReference>empty()
                                : profile.weeklySchedule().days().stream()
                                        .filter(d -> d != null && d.references() != null)
                                        .flatMap(d -> d.references().stream()))
                .filter(r -> r != null && r.dHashes() != null)
                .forEach(r -> r.dHashes().stream()
                        .filter(h -> h == null || !WELL_FORMED_DHASH.matcher(h).matches())
                        .forEach(h -> faults.add(describeMalformedHash(r, h))));
        if (!faults.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    igAccount + ": a marker reference carries something that is not a perceptual hash — "
                            + "it would be silently unmatchable. " + String.join("; ", faults));
        }
    }

    /** Name the fault in terms an administrator can act on, rather than as a length assertion. */
    private static String describeMalformedHash(VettedProfile.TypedMarkerReference ref, String hash) {
        String where = (ref.markerType() == null ? "?" : ref.markerType())
                + (ref.ocrText() == null || ref.ocrText().isBlank() ? "" : " \"" + ref.ocrText() + "\"");
        if (hash == null) {
            return where + ": null";
        }
        // The one that has actually happened: a post identifier written into the hash field.
        if (hash.equals(ref.shortcode())) {
            return where + ": the post shortcode (" + hash + ") was stored as the hash";
        }
        if (hash.matches("[A-Za-z0-9_-]{11,44}") && !hash.matches("[01]+")) {
            return where + ": '" + hash + "' looks like an Instagram post shortcode, not a hash";
        }
        return where + ": '" + hash + "' is not 64 binary digits";
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
        return recordDrift(igAccount, kind, reporterDeviceId, reporterUserId, nominatedOwnerHandle, agreePass,
                disagreePass, persistenceCount, null, null, null, null, null, null, null);
    }

    /** Compatibility overload for callers with a measurement but no reference text / fault detail. */
    @Transactional
    public DriftObservation recordDrift(String igAccount, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                                        String nominatedOwnerHandle, Integer agreePass, Integer disagreePass,
                                        Integer persistenceCount, String markerRole, Integer imageDistance,
                                        Integer imageThreshold, String evidencePostId, byte[] evidenceImage) {
        return recordDrift(igAccount, kind, reporterDeviceId, reporterUserId, nominatedOwnerHandle, agreePass,
                disagreePass, persistenceCount, markerRole, null, null, imageDistance, imageThreshold,
                evidencePostId, evidenceImage);
    }

    /**
     * As above, carrying the MEASURED drift and the picture the marker was actually posted with.
     *
     * <p>The picture is stored content-addressed via {@link MarkerImageStore}, the same store the vetted profile's
     * display images use, so the admin UI can fetch it by locator through the existing endpoint. It arrives from the
     * CLIENT and only from the client — directive B1: no cloud service ever contacts Instagram.</p>
     */
    @Transactional
    public DriftObservation recordDrift(String igAccount, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                                        String nominatedOwnerHandle, Integer agreePass, Integer disagreePass,
                                        Integer persistenceCount, String markerRole, String markerText, String detail,
                                        Integer imageDistance, Integer imageThreshold, String evidencePostId,
                                        byte[] evidenceImage) {
        return recordDrift(igAccount, kind, reporterDeviceId, reporterUserId, nominatedOwnerHandle, agreePass,
                disagreePass, persistenceCount, markerRole, markerText, detail, imageDistance, imageThreshold,
                evidencePostId, evidenceImage, null);
    }

    /**
     * As above, carrying THE CLIENT'S OWN fingerprint of the delivered picture.
     *
     * <p>Stored verbatim and never recomputed: our {@code ImageDHash} lands 15–34 bits away for identical bytes
     * (measured 16/08/2026) while clients match at 4–10, so a hash produced here looks healthy and can never
     * match. The picture and its fingerprint are also filed in the durable {@link ClientMarkerImage} catalogue, so
     * an operator can choose this exact rendition at a later vetting without ordering a duty scrape.</p>
     */
    @Transactional
    public DriftObservation recordDrift(String igAccount, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                                        String nominatedOwnerHandle, Integer agreePass, Integer disagreePass,
                                        Integer persistenceCount, String markerRole, String markerText, String detail,
                                        Integer imageDistance, Integer imageThreshold, String evidencePostId,
                                        byte[] evidenceImage, String evidenceImageHash) {
        return recordDrift(igAccount, kind, reporterDeviceId, reporterUserId, nominatedOwnerHandle, agreePass,
                disagreePass, persistenceCount, markerRole, markerText, detail, imageDistance, imageThreshold,
                evidencePostId, evidenceImage, evidenceImageHash, null);
    }

    /**
     * As above, carrying the WEEKDAY SLOT the drifting reference belongs to (JS 0=Sun … 6=Sat) — resolved by the
     * CLIENT from the marker's own postedOn through the schedule's day-offsets, and stored as part of the
     * observation's identity. {@code null} = flat/legacy group or an old client; the client fails closed on an
     * unresolvable slot, so a wrong value never arrives.
     */
    @Transactional
    public DriftObservation recordDrift(String igAccount, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                                        String nominatedOwnerHandle, Integer agreePass, Integer disagreePass,
                                        Integer persistenceCount, String markerRole, String markerText, String detail,
                                        Integer imageDistance, Integer imageThreshold, String evidencePostId,
                                        byte[] evidenceImage, String evidenceImageHash, Integer markerWeekday) {
        if (kind == DriftKind.NEW_OWNER && (nominatedOwnerHandle == null || nominatedOwnerHandle.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "nominatedOwnerHandle is required for a NEW_OWNER drift");
        }
        SupportGroupConfig c = require(igAccount);

        // A CORRUPT-reference report is checkable against the truth we hold — so check it, rather than taking a
        // client's word.
        //
        // A client reports against the profile snapshot it last pulled, and adopts a new version only on its next
        // poll. So every repair has a window in which a client still running on the old snapshot re-reports the
        // fault we just fixed — and `observeAgain` would re-open the resolved observation, putting the group back
        // into "needs re-vet" over something that no longer exists. Worse, there is then no way out: Repair finds
        // nothing malformed and answers 409. Observed live on `glowbloggeragency` (16/08/2026), one minute after
        // a successful repair.
        if (kind == DriftKind.MARKER_REFERENCE_CORRUPT && !referenceIsActuallyCorrupt(c, markerRole, markerText)) {
            resolveStaleCorruptionReports(c, markerRole, markerText);
            return null;
        }
        String handle = kind == DriftKind.NEW_OWNER ? nominatedOwnerHandle : null;
        Instant now = Instant.now();
        // ⚠️ The REFERENCE is part of the natural key. A per-weekday group carries several banners per kind and
        // they drift independently; keying on the reporter alone meant every one of them upserted into the same
        // row and only the last reported survived (live, 18/08/2026: three drifting references arrived, one was
        // shown, and not the worst).
        Optional<DriftObservation> existing = handle == null
                ? driftObservations.findForReference(c.getId(), kind, reporterDeviceId, markerWeekday, markerRole,
                        markerText)
                : driftObservations.findByConfigIdAndKindAndReporterDeviceIdAndNominatedOwnerHandle(
                        c.getId(), kind, reporterDeviceId, handle);
        DriftObservation obs = existing
                .map(o -> {
                    o.observeAgain(agreePass, disagreePass, persistenceCount, now);
                    return o;
                })
                .orElseGet(() -> DriftObservation.first(c.getId(), kind, reporterDeviceId, reporterUserId, handle,
                        markerWeekday, agreePass, disagreePass, persistenceCount, now));
        // Best-effort capture: a picture that cannot be stored must not reject the report. The measurement is the
        // signal; the picture is what makes it actionable in one click.
        String locator = evidenceImage == null || evidenceImage.length == 0
                ? null
                : markerImageStore.capture(evidenceImage).orElse(null);
        obs.measure(markerRole, markerText, imageDistance, imageThreshold, evidencePostId, locator, detail,
                evidenceImageHash);
        // File it durably. Observations are a work queue and get pruned; the picture is evidence with a long life,
        // and B1 means nothing can go and fetch it again.
        rememberClientMarkerImage(c.getId(), evidenceImageHash, locator, markerRole, markerText, evidencePostId, now);
        return driftObservations.save(obs);
    }

    /**
     * M5 re-vet consumer admin "acknowledge" (the "I reviewed, it's fine" path for a NON-severe drift): resolve the
     * config's open marker-disagree observations WITHOUT a re-vet, clearing the derived needs-re-vet flag. No config
     * mutation + no ETag bump (the config is not re-shipped to clients) — a fresh drift re-raises the flag. Idempotent:
     * a config with no open observations is a no-op. Returns the (unchanged) config.
     */
    /**
     * Adopt the picture a drifted marker is ACTUALLY being posted with: hash the delivered image and <strong>ADD</strong>
     * that hash to the reference the drift names. Resolves the observation.
     *
     * <p><strong>It appends; it never replaces.</strong> {@code dHashes} is a list precisely so a reference can hold
     * every version of its banner. Keeping the old hash keeps older posts matching, and — measured on
     * `glowbloggeragency` (16/08/2026) — it also feeds the client's threshold calibration the evidence it was
     * missing: with BOTH pictures present the calibration can see that Sunday's START and the ENDE banner are only 9
     * bits apart and tightens its floor from 10 to 8 by itself. Replacing would have kept the floor at 10 and left
     * the misread possible.</p>
     *
     * <p>The reference is matched by ROLE and, where the drift names one, by the OCR text — a per-weekday group
     * carries several different banners for one role, and adopting Sunday's picture into Monday's reference would
     * be worse than doing nothing.</p>
     */
    @Transactional
    public SupportGroupConfig adoptDriftedMarkerImage(String igAccount, UUID observationId) {
        SupportGroupConfig c = require(igAccount);
        DriftObservation obs = driftObservations.findById(observationId)
                .filter(o -> o.getConfigId().equals(c.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no such drift observation for " + igAccount));
        if (obs.getEvidenceImageLocator() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "this drift carries no picture, so there is nothing to adopt");
        }
        VettedProfile profile = c.getVettedProfile();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, igAccount + " has no vetted profile to add to");
        }
        // ⚠️ THE CLIENT'S HASH, NEVER OURS. Measured 16/08/2026: ImageDHash lands 15–34 bits from the client's
        // fingerprint for identical bytes — as far apart as unrelated images — while clients match live posts at a
        // 4–10 bit tolerance. A hash computed here would be written into the authoritative profile looking
        // perfectly healthy and would never match anything. Cross-PLATFORM agreement is proven (0 bits on
        // ubuntu/windows/macos with real Instagram images); crossing IMPLEMENTATIONS is what breaks.
        String newHash = obs.getEvidenceImageHash();
        if (newHash == null || !WELL_FORMED_DHASH.matcher(newHash).matches()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "this drift carries no client-computed hash for its picture, and this service must not compute "
                    + "one — its fingerprint would not match what clients produce. A newer client reports it; "
                    + "adopt once one has.");
        }
        if (markerImageStore.find(obs.getEvidenceImageLocator()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the drift's picture is no longer stored");
        }
        // Repoints the DISPLAY image too: the hash list keeps every version, but what a human looks at should be
        // what the owner is posting today.
        VettedProfile updated = VettedProfileHashAdopter.append(
                profile, obs.getMarkerWeekday(), obs.getMarkerRole(), obs.getMarkerText(), newHash,
                obs.getEvidenceImageLocator());
        applyVettedProfile(igAccount, c, updated);
        SupportGroupConfig saved = configs.save(c);
        obs.resolve();
        driftObservations.save(obs);
        return saved;
    }

    /**
     * Admin "repair this reference" — recompute a malformed dHash from the reference's OWN stored picture.
     *
     * <p><strong>This needs nothing from Instagram.</strong> A corrupt reference is a data fault, not a stale one:
     * the picture it was vetted from is already held against its {@code imageLocator}, so the hash that should have
     * been stored is recomputable in place. That is what makes this a one-click repair rather than a re-vet, and it
     * is why the remedy differs from {@link #adoptDriftedMarkerImage}'s.</p>
     *
     * <p><strong>The malformed value is REMOVED, not kept.</strong> Everywhere else in this loop the rule is
     * "append, never replace", because an older picture is still a real picture. A value that is not a hash is not
     * evidence of anything — it matches nothing, contradicts nothing, and silently distorts the client's threshold
     * calibration — so it is dropped. A reference whose picture is missing is left exactly as it is and reported,
     * rather than being quietly emptied.</p>
     *
     * <p>Resolves the config's open {@link DriftKind#MARKER_REFERENCE_CORRUPT} observations. 409 when nothing could
     * be repaired, naming what stood in the way — a silent no-op would read as success.</p>
     */
    @Transactional
    public SupportGroupConfig repairMalformedReferenceHashes(String igAccount) {
        SupportGroupConfig c = require(igAccount);
        VettedProfile profile = c.getVettedProfile();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, igAccount + " has no vetted profile to repair");
        }
        List<String> unrepairable = new ArrayList<>();
        // Read the doomed values BEFORE the repair drops them — afterwards there is nothing left to name.
        List<String> corruptValues = malformedHashesOf(profile);
        VettedProfile repaired = VettedProfileHashRepairer.repair(profile, r -> clientHashFor(igAccount, c, r), unrepairable);
        List<DriftObservation> open = driftObservations.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT);

        // ONE decisive click. Repairing the field and adopting the live banner are the same operator intent —
        // "make this reference right" — so they happen together rather than as two buttons whose ordering the
        // administrator has to reason about.
        //
        // The live picture is only ADDED when it differs from the repaired one. Identical pictures need no second
        // entry (the adopter dedups anyway); a DIFFERENT one is a genuinely different rendition of the same
        // banner, and carrying both is what widens what can be recognised — measured on glow as a threshold floor
        // dropping 10 → 8 once both were present.
        VettedProfile withLive = repaired == null ? null : repaired;
        for (DriftObservation o : open) {
            // A live banner is a BONUS, never a precondition: a picture that was never delivered, or has since
            // been reclaimed, must not stop the field itself being repaired. Its fingerprint is the CLIENT's — an
            // older observation predating the client that sends one carries no hash, and the picture is then
            // simply not adopted, because we have no fingerprint anyone could match it by.
            String liveHash = wellFormedClientHash(o.getEvidenceImageHash()).orElse(null);
            boolean pictureStillHeld = o.getEvidenceImageLocator() != null
                    && markerImageStore.find(o.getEvidenceImageLocator()).isPresent();
            if (liveHash != null && withLive != null && pictureStillHeld) {
                withLive = VettedProfileHashAdopter.append(withLive, o.getMarkerRole(), o.getMarkerText(), liveHash,
                        o.getEvidenceImageLocator());
            }
        }

        if (withLive == profile) {
            // Nothing to repair — but that is not necessarily nothing to DO. An observation can outlive the fault
            // it describes: a client reporting against a snapshot it had not yet re-pulled re-opens a resolved
            // one, and then nothing ever closes it, because the client has since adopted the fix and will never
            // report it again. The ingest guard cannot help — it only fires on a report that never comes.
            //
            // So the button clears what it can instead of refusing. Observed live on `glowbloggeragency`
            // (16/08/2026): repaired, re-opened a minute later by a stale report, and then stuck with Repair
            // answering 409 and Acknowledge covering only marker-disagree.
            List<DriftObservation> stale = openCorruptionObservationsNowFine(c);
            if (!stale.isEmpty()) {
                stale.forEach(DriftObservation::resolve);
                driftObservations.saveAll(stale);
                return c;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, unrepairable.isEmpty()
                    ? igAccount + ": every marker reference already carries a well-formed hash — nothing to repair"
                    : igAccount + ": nothing could be repaired. " + String.join("; ", unrepairable));
        }
        applyVettedProfile(igAccount, c, withLive);
        SupportGroupConfig saved = configs.save(c);
        // ⚠️ BURN THE SOURCE, not just the symptom. The corrupt value was picked in the Vetting Portal from a
        // corpus tile, and repairing the profile leaves that tile sitting there, clickable, ready to write the
        // same value back on the next re-vet. Flagging it is the difference between fixing this occurrence and
        // fixing the fault. (User's requirement, 18/08/2026: it "never can be clicked and added to the vetted
        // profile again".)
        corruptValues.forEach(v -> corpus.burn(igAccount, v,
                "picked as a marker reference and found to be no fingerprint at all — repaired " + Instant.now()));
        open.forEach(DriftObservation::resolve);
        driftObservations.saveAll(open);
        return saved;
    }

    /** Every value a profile stores where a fingerprint belongs but which is not one. */
    private static List<String> malformedHashesOf(VettedProfile profile) {
        return allReferences(profile)
                .filter(r -> r != null && r.dHashes() != null)
                .flatMap(r -> r.dHashes().stream())
                .filter(h -> h != null && !h.isBlank() && !WELL_FORMED_DHASH.matcher(h).matches())
                .distinct()
                .toList();
    }

    /** Open corruption observations whose named reference demonstrably carries a well-formed hash today. */
    private List<DriftObservation> openCorruptionObservationsNowFine(SupportGroupConfig c) {
        return driftObservations.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                        c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT).stream()
                .filter(o -> !referenceIsActuallyCorrupt(c, o.getMarkerRole(), o.getMarkerText()))
                .toList();
    }

    /**
     * A fingerprint a CLIENT computed for a reference — never one computed here.
     *
     * <p><strong>The live picture a client delivered comes FIRST, and that is the whole design.</strong> A client
     * that reports a corrupt reference sends the banner the owner is posting <em>today</em> together with its own
     * fingerprint of it. The two arrive as a matched pair, they describe the same bytes, and they describe what
     * clients actually have to recognise now. Adopting them is what the loop exists for — and it makes the
     * reference BETTER, not merely well-formed again, because the profile ends up carrying a current picture.</p>
     *
     * <ol>
     *   <li><strong>the live picture a client delivered</strong> with an open corruption report, matched on role
     *       and text;</li>
     *   <li>the deep-scrape corpus, keyed by the reference's own {@code shortcode} — a <em>fallback only</em>.</li>
     * </ol>
     *
     * <p>⚠️ <strong>Why the corpus is a fallback and not the default.</strong> Two reasons, and the second is
     * measured:</p>
     * <ul>
     *   <li>it is <strong>coincidental</strong>. Nothing guarantees the corpus still holds the reference's post —
     *       snapshots are pruned to a retention window, a pass may have been voided, the row may be burned. A
     *       source that is usually absent is not a default.</li>
     *   <li>it may describe a <strong>different rendition of the same picture</strong>. A corpus row is hashed
     *       from what the client streamed off the tagged grid; a vetted reference's stored picture is often the
     *       smaller marker rendition. Measured 16/08/2026 on glow's own Sunday START: the full post image
     *       (1187×1304) and the thumbnail (233×256) of the SAME banner are <strong>15 bits apart</strong> — and
     *       clients accept a match at 4–10. So a corpus hash can be perfectly valid, perfectly client-computed,
     *       and still not describe the picture it is being attached to.</li>
     * </ul>
     *
     * <p>Empty means empty. There is no third source that mints one locally — that is the bug, not the
     * backstop.</p>
     */
    private Optional<String> clientHashFor(String igAccount, SupportGroupConfig c,
                                           VettedProfile.TypedMarkerReference r) {
        Optional<String> fromLiveReport = driftObservations
                .findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(c.getId(),
                        DriftKind.MARKER_REFERENCE_CORRUPT).stream()
                .filter(o -> describes(o, r))
                .map(DriftObservation::getEvidenceImageHash)
                .flatMap(h -> wellFormedClientHash(h).stream())
                .findFirst();
        if (fromLiveReport.isPresent()) {
            return fromLiveReport;
        }
        return corpus.clientHashForPost(igAccount, r.shortcode())
                .flatMap(SupportGroupConfigService::wellFormedClientHash);
    }

    /** Whether a report is about this very reference — by role AND text, never role alone (glow has two STARTs). */
    private static boolean describes(DriftObservation o, VettedProfile.TypedMarkerReference r) {
        boolean sameRole = o.getMarkerRole() == null || r.markerType() == null
                || o.getMarkerRole().equalsIgnoreCase(r.markerType());
        boolean sameText = o.getMarkerText() == null || r.ocrText() == null
                || o.getMarkerText().trim().equalsIgnoreCase(r.ocrText().trim());
        return sameRole && sameText;
    }

    /** A client's value is taken verbatim or not at all — but it still has to BE a fingerprint. */
    private static Optional<String> wellFormedClientHash(String hash) {
        return hash != null && WELL_FORMED_DHASH.matcher(hash).matches() ? Optional.of(hash) : Optional.empty();
    }

    @Transactional
    public SupportGroupConfig acknowledgeRevet(String igAccount) {
        SupportGroupConfig c = require(igAccount);
        resolveMarkerDisagreeObservations(c.getId());
        return c;
    }

    /** Resolve a config's open marker-disagree observations (a re-vet clears the derived "needs re-vet" flag). */
    private void resolveMarkerDisagreeObservations(UUID configId) {
        List<DriftObservation> open = driftObservations
                .findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(configId, DriftKind.MARKER_DISAGREE);
        open.forEach(DriftObservation::resolve);
        driftObservations.saveAll(open);
    }

    /** The config's derived re-vet status (M5): any unresolved drift (marker-disagree and/or new-owner) flags it + aggregates a reason per kind. */
    @Transactional(readOnly = true)
    public RevetStatus revetStatus(SupportGroupConfig c) {
        return revetStatusOf(driftObservations.findByConfigIdAndResolvedFalse(c.getId()));
    }

    /**
     * The re-vet status of every given config (M5 admin list): one query fetches all their unresolved drift
     * observations (every kind), grouped in memory, so the list endpoint never issues a per-row query. Every config id
     * maps to a status (a config with no open observations maps to {@link RevetStatus#none()}).
     */
    @Transactional(readOnly = true)
    public Map<UUID, RevetStatus> revetStatuses(Collection<SupportGroupConfig> cs) {
        List<UUID> ids = cs.stream().map(SupportGroupConfig::getId).toList();
        Map<UUID, List<DriftObservation>> byConfig = ids.isEmpty()
                ? Map.of()
                : driftObservations.findByConfigIdInAndResolvedFalse(ids).stream()
                        .collect(Collectors.groupingBy(DriftObservation::getConfigId));
        Map<UUID, RevetStatus> out = new HashMap<>();
        for (SupportGroupConfig c : cs) {
            out.put(c.getId(), revetStatusOf(byConfig.getOrDefault(c.getId(), List.of())));
        }
        return out;
    }

    /**
     * Aggregate a config's open drift observations into a re-vet status ({@code none} when there are none): one
     * {@link RevetReason} per kind present, so the admin can see every reason at once.
     *
     * <p><strong>Order is severity, not chronology.</strong> A CORRUPT reference comes first: it is a data fault that
     * makes a reference permanently unmatchable, and it is silent — no scan will ever complain again. A MEASURED
     * image drift comes next: it is actionable in one click and it is what the demotion tally can no longer see.</p>
     *
     * <p>⚠️ Every kind must appear in this list. A kind omitted here is stored, listed by the drift endpoint, and
     * still <em>invisible</em> on the needs-re-vet surface — which is exactly how MARKER_IMAGE_DRIFT shipped in #66:
     * the loop's notification level was silent because this stream named only the two original kinds.</p>
     */
    private RevetStatus revetStatusOf(List<DriftObservation> open) {
        if (open.isEmpty()) {
            return RevetStatus.none();
        }
        Map<DriftKind, List<DriftObservation>> byKind = open.stream().collect(Collectors.groupingBy(DriftObservation::getKind));
        List<RevetReason> reasons = Stream.of(DriftKind.values())
                .sorted(Comparator.comparingInt(SupportGroupConfigService::severityOf))
                .map(byKind::get)
                .filter(l -> l != null && !l.isEmpty())
                .map(SupportGroupConfigService::reasonOf)
                .toList();
        return new RevetStatus(true, reasons);
    }

    /** Display severity of a drift kind — lower sorts first on the admin surface. */
    private static int severityOf(DriftKind kind) {
        return switch (kind) {
            case MARKER_REFERENCE_CORRUPT -> 0; // a permanently unmatchable reference, and silent
            case MARKER_IMAGE_DRIFT -> 1;       // real, measured, one click to fix
            case MARKER_DISAGREE -> 2;
            case NEW_OWNER -> 3;
        };
    }

    /** Aggregate one kind's open observations into its {@link RevetReason}. */
    private static RevetReason reasonOf(List<DriftObservation> ofKind) {
        DriftKind kind = ofKind.get(0).getKind();
        long totalOccurrences = ofKind.stream().mapToLong(DriftObservation::getOccurrenceCount).sum();
        int distinctReporters = (int) ofKind.stream().map(DriftObservation::getReporterDeviceId).distinct().count();
        Instant firstSeen = ofKind.stream().map(DriftObservation::getFirstSeenAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant lastSeen = ofKind.stream().map(DriftObservation::getLastSeenAt).max(Comparator.naturalOrder()).orElseThrow();
        if (kind == DriftKind.NEW_OWNER) {
            List<String> handles = ofKind.stream().map(DriftObservation::getNominatedOwnerHandle)
                    .filter(Objects::nonNull).distinct().sorted().toList();
            return new RevetReason(kind, distinctReporters, totalOccurrences, null, null, null, handles, firstSeen, lastSeen);
        }
        DriftObservation latest = ofKind.stream().max(Comparator.comparing(DriftObservation::getLastSeenAt)).orElseThrow();
        Integer maxPersistence = ofKind.stream()
                .map(DriftObservation::getPersistenceCount).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(null);
        return new RevetReason(kind, distinctReporters, totalOccurrences, latest.getAgreePass(),
                latest.getDisagreePass(), maxPersistence, List.of(), firstSeen, lastSeen);
    }

    /**
     * Does the STORED profile still carry a malformed hash on the reference this report names?
     *
     * <p>Unknown references and a profile-less config answer {@code true} — we only contradict a client when we can
     * positively see that the fault is gone, never when we simply cannot find what it is talking about.</p>
     */
    private boolean referenceIsActuallyCorrupt(SupportGroupConfig c, String markerRole, String markerText) {
        VettedProfile profile = c.getVettedProfile();
        if (profile == null) {
            return true;
        }
        List<VettedProfile.TypedMarkerReference> named = matchingReferences(profile,
                markerRole, markerText).toList();
        if (named.isEmpty()) {
            return true;
        }
        return named.stream().anyMatch(r -> r.dHashes() == null || r.dHashes().stream()
                .anyMatch(h -> h == null || !WELL_FORMED_DHASH.matcher(h).matches()));
    }

    /** Close any open corruption observation for a reference that is demonstrably fine now. */
    private void resolveStaleCorruptionReports(SupportGroupConfig c, String markerRole, String markerText) {
        List<DriftObservation> open = driftObservations.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT).stream()
                .filter(o -> sameReference(o, markerRole, markerText))
                .toList();
        if (!open.isEmpty()) {
            open.forEach(DriftObservation::resolve);
            driftObservations.saveAll(open);
        }
    }

    private static boolean sameReference(DriftObservation o, String markerRole, String markerText) {
        return blankOrEquals(o.getMarkerRole(), markerRole) && blankOrEquals(o.getMarkerText(), markerText);
    }

    private static boolean blankOrEquals(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        return x.isEmpty() || y.isEmpty() || x.equalsIgnoreCase(y);
    }

    /**
     * File a client-delivered picture in the durable catalogue, keyed by ITS OWN hash.
     *
     * <p>Requires both the picture and the client's fingerprint: a picture with no usable hash cannot be offered
     * as a reference (that is the fault this whole line of work exists to prevent), so it is not catalogued. Seen
     * again, the row is refreshed rather than duplicated — one banner, however many scans observe it.</p>
     */
    private void rememberClientMarkerImage(UUID configId, String dHash, String locator, String markerRole,
                                           String markerText, String evidencePostId, Instant now) {
        if (dHash == null || !WELL_FORMED_DHASH.matcher(dHash).matches() || locator == null || locator.isBlank()) {
            return;
        }
        clientMarkerImages.findByConfigIdAndDHash(configId, dHash)
                .map(existing -> {
                    existing.seenAgain(markerRole, markerText, evidencePostId, now);
                    return existing;
                })
                .or(() -> Optional.of(ClientMarkerImage.first(configId, dHash, locator, markerRole, markerText,
                        evidencePostId, now)))
                .ifPresent(clientMarkerImages::save);
    }

    /** Every marker picture clients have delivered for this group — the later-vetting picker, newest first. */
    @Transactional(readOnly = true)
    public List<ClientMarkerImage> clientMarkerImages(String igAccount) {
        return clientMarkerImages.findByConfigIdOrderByLastSeenAtDesc(require(igAccount).getId());
    }

    /** The two kinds that describe the HEALTH of a marker reference, as opposed to who owns a marker. */
    private static final List<DriftKind> REFERENCE_HEALTH_KINDS =
            List.of(DriftKind.MARKER_IMAGE_DRIFT, DriftKind.MARKER_REFERENCE_CORRUPT);

    /**
     * Admin: a config's open marker-REFERENCE observations, newest-seen first — the surface behind the
     * "this banner changed, adopt the new picture" and "this reference is corrupt, repair it" prompts.
     *
     * <p>A MEASURED drift carries the distance, the reference it belongs to and a locator for the picture the owner
     * is actually posting; a CORRUPT reference carries the malformed value and why it is malformed. New-owner
     * nominations are deliberately NOT included — they are a question about people, have their own review surface
     * ({@link #newOwnerNominations}), and would only appear twice here.</p>
     */
    @Transactional(readOnly = true)
    public List<ReferenceDriftView> markerReferenceDrifts(String igAccount) {
        SupportGroupConfig c = require(igAccount);
        List<DriftObservation> open = driftObservations.findByConfigIdAndKindInAndResolvedFalseOrderByLastSeenAtDesc(
                c.getId(), REFERENCE_HEALTH_KINDS);
        return open.stream().map(o -> {
            String referenceLocator = referencePictureFor(c, o);
            // ⚠️ BOTH SIDES MUST BE IN THE CLIENT'S DIALECT or the distance between them is meaningless. Until
            // 18/08/2026 both were hashed here with ImageDHash: self-consistent, and 15–34 bits away from what
            // every client measures for the same pictures — so the figure an administrator read while deciding
            // "is this a different banner or the same one?" was not the figure their clients would ever see.
            String referenceHash = clientHashForReferenceOf(c, o).orElse(null);
            String liveHash = wellFormedClientHash(o.getEvidenceImageHash()).orElse(null);
            return new ReferenceDriftView(o, referenceLocator, referenceHash, liveHash,
                    storedValueFor(c, o), hamming(referenceHash, liveHash));
        }).toList();
    }

    /**
     * One open reference drift, paired with THE PICTURE THE REMEDY WILL USE — so the admin surface can show what is
     * about to be written rather than describing it.
     *
     * <p>Both kinds now adopt the banner the CLIENT captured ({@code evidenceImageLocator} on the observation).
     * That was not always true: a corrupt reference used to be repaired from its OWN stored picture, with nothing
     * new arriving and the hash merely recomputed from bytes already held. Two things killed that design — the
     * cloud cannot compute a fingerprint clients can match, and the client is already sending a current picture
     * WITH its own fingerprint. Taking that pair makes the reference better rather than merely well-formed
     * again.</p>
     *
     * <p>{@code referenceImageLocator} is therefore the BEFORE picture — what the profile holds today, shown so
     * the operator can see what is being replaced, not what is being written.</p>
     */
    public record ReferenceDriftView(
            DriftObservation observation,
            /** The reference's OWN stored picture — the BEFORE, i.e. what is being replaced. */
            String referenceImageLocator,
            /** The client-computed fingerprint the profile currently holds for it, where one is known. */
            String referenceImageHash,
            /** The CLIENT's fingerprint of the live banner — exactly what the remedy will write. */
            String evidenceImageHash,
            /** What the reference stores TODAY. For a corrupt reference this is the value that is not a hash. */
            String storedValue,
            /**
             * How far the live banner sits from the vetted picture, in bits — {@code null} when either is absent.
             *
             * <p>Turns "should I also add the live picture?" from a judgement into a fact an administrator can
             * read: <strong>0</strong> means the two pictures are the same and adding buys nothing;
             * anything higher means the live banner is a genuinely different rendition and carrying both widens
             * what can be recognised.</p>
             */
            Integer liveDistance) {
    }

    /**
     * The client-computed fingerprint the profile currently holds for the reference an observation names — the
     * BEFORE side of the comparison.
     *
     * <p>Read from the vetted profile itself when it already holds a well-formed one (every hash cut from a deep
     * scrape is the client's), and otherwise from the corpus by the reference's post. Never hashed here, and
     * deliberately NOT the live report's hash: comparing the live banner against itself would always read 0 bits
     * and tell the operator nothing.</p>
     */
    private Optional<String> clientHashForReferenceOf(SupportGroupConfig c, DriftObservation o) {
        if (c.getVettedProfile() == null) {
            return Optional.empty();
        }
        return matchingReferences(c.getVettedProfile(), o)
                .flatMap(r -> Stream.concat(
                        r.dHashes() == null ? Stream.<String>empty() : r.dHashes().stream(),
                        corpus.clientHashForPost(c.getIgAccount(), r.shortcode()).stream()))
                .flatMap(h -> wellFormedClientHash(h).stream())
                .findFirst();
    }

    /** Hamming distance between two equal-length dHash strings, or {@code null} when either is missing. */
    private static Integer hamming(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return null;
        }
        int d = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                d++;
            }
        }
        return d;
    }

    /**
     * The value the named reference currently stores — shown beside the two real hashes so the fault is visible
     * rather than described. For glow that is a 39-character post shortcode sitting where 64 binary digits belong;
     * side by side with the hashes its own picture produces, no explanation is needed.
     */
    private String storedValueFor(SupportGroupConfig c, DriftObservation o) {
        if (c.getVettedProfile() == null) {
            return null;
        }
        return matchingReferences(c.getVettedProfile(), o)
                .filter(r -> r.dHashes() != null && !r.dHashes().isEmpty())
                .map(r -> r.dHashes().get(0))
                .findFirst()
                .orElse(null);
    }

    /**
     * The picture the remedy will hash for this observation, or {@code null} when there is none to show.
     *
     * <p>For a corrupt reference that means locating the reference the drift NAMES (by role, and by text where the
     * client reported one) and returning its own stored image. A null here is itself informative: it means the
     * repair has nothing to recompute from, and the admin surface should say so rather than offer a button that
     * will 409.</p>
     */
    private String referencePictureFor(SupportGroupConfig c, DriftObservation o) {
        if (c.getVettedProfile() == null) {
            return null;
        }
        return matchingReferences(c.getVettedProfile(), o)
                .filter(r -> r.imageLocator() != null && !r.imageLocator().isBlank())
                .map(VettedProfile.TypedMarkerReference::imageLocator)
                .findFirst()
                .orElse(null);
    }

    /** The references a drift NAMES — by role, and by text where the client reported one. */
    private static Stream<VettedProfile.TypedMarkerReference> matchingReferences(VettedProfile profile,
                                                                                DriftObservation o) {
        // ⚠️ Scoped to the observation's WEEKDAY when it carries one (19/08/2026) — the third day-blind surface
        // of the same disease. Adoption writes one day's slot and the measurement judges against it, but this
        // resolver flattened every day and showed the FIRST role+text match: Tuesday's card displayed MONDAY's
        // stored value while Tuesday's slot verifiably held the freshly-adopted hash, making a ghost card look
        // legitimate. What the card claims the profile holds must come from the same slot the remedy writes to.
        // No weekday (legacy row, corrupt-reference kind, flat group) keeps the flattened behaviour.
        Stream<VettedProfile.TypedMarkerReference> candidates = o.getMarkerWeekday() == null
                ? allReferences(profile)
                : dayReferences(profile, o.getMarkerWeekday());
        return filterByRoleAndText(candidates, o.getMarkerRole(), o.getMarkerText());
    }

    /** ONE weekday slice's references — the slot adoption writes to and the drift card must therefore describe. */
    private static Stream<VettedProfile.TypedMarkerReference> dayReferences(VettedProfile profile, int weekday) {
        if (profile.weeklySchedule() == null || profile.weeklySchedule().days() == null) {
            return Stream.empty();
        }
        return profile.weeklySchedule().days().stream()
                .filter(d -> d != null && d.weekday() == weekday && d.references() != null)
                .flatMap(d -> d.references().stream());
    }

    private static Stream<VettedProfile.TypedMarkerReference> matchingReferences(VettedProfile profile,
                                                                                String markerRole, String markerText) {
        return filterByRoleAndText(allReferences(profile), markerRole, markerText);
    }

    private static Stream<VettedProfile.TypedMarkerReference> filterByRoleAndText(
            Stream<VettedProfile.TypedMarkerReference> candidates, String markerRole, String markerText) {
        return candidates
                .filter(java.util.Objects::nonNull)
                .filter(r -> markerRole == null || markerRole.isBlank()
                        || markerRole.equalsIgnoreCase(r.markerType()))
                .filter(r -> markerText == null || markerText.isBlank()
                        || markerText.trim().equalsIgnoreCase(
                                r.ocrText() == null ? "" : r.ocrText().trim()));
    }

    /** Every reference in a profile — the detector block and every weekday — as one stream. */
    private static Stream<VettedProfile.TypedMarkerReference> allReferences(VettedProfile profile) {
        return Stream.concat(
                profile.detector() == null || profile.detector().references() == null
                        ? Stream.empty() : profile.detector().references().stream(),
                profile.weeklySchedule() == null || profile.weeklySchedule().days() == null
                        ? Stream.empty() : profile.weeklySchedule().days().stream()
                                .filter(d -> d != null && d.references() != null)
                                .flatMap(d -> d.references().stream()));
    }

    /** A config's open new-owner nominations, newest-seen first (M5 admin review-candidate surface). */
    @Transactional(readOnly = true)
    public List<DriftObservation> newOwnerNominations(String igAccount) {
        SupportGroupConfig c = require(igAccount);
        return driftObservations.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(c.getId(), DriftKind.NEW_OWNER);
    }

    /**
     * Admin "Add" on a new-owner nomination (M5.20): incorporate the nominated handle into the config's vetted marker
     * owners — {@link SupportGroupConfig#addMarkerOwner} is idempotent and bumps the version, so the client adopts it on
     * its next poll — and resolve that handle's open nominations so it drops off the review list. Returns the (mutated)
     * config.
     */
    @Transactional
    public SupportGroupConfig confirmNomination(String igAccount, String handle) {
        SupportGroupConfig c = require(igAccount);
        c.addMarkerOwner(handle);
        resolveNewOwnerNominations(c.getId(), handle);
        return configs.save(c);
    }

    /**
     * Admin "Dismiss" on a new-owner nomination (M5.20): it is NOT a marker owner — resolve that handle's open
     * nominations so it drops off the review list, without touching the owner set or the config version. A later
     * re-nomination re-opens it. Returns the (unchanged) config.
     */
    @Transactional
    public SupportGroupConfig dismissNomination(String igAccount, String handle) {
        SupportGroupConfig c = require(igAccount);
        resolveNewOwnerNominations(c.getId(), handle);
        return c;
    }

    /** Resolve every reporter's open new-owner nomination of {@code handle} for a config (confirm/dismiss share this). */
    private void resolveNewOwnerNominations(UUID configId, String handle) {
        List<DriftObservation> open = driftObservations
                .findByConfigIdAndKindAndNominatedOwnerHandleAndResolvedFalse(configId, DriftKind.NEW_OWNER, handle);
        open.forEach(DriftObservation::resolve);
        driftObservations.saveAll(open);
    }

    /**
     * A config's derived re-vet status (M5): whether it needs re-vetting + a {@link RevetReason} per open drift kind
     * (marker-disagree and/or new-owner). {@code reasons} is empty when it does not need re-vetting.
     */
    public record RevetStatus(boolean needsRevet, List<RevetReason> reasons) {
        static RevetStatus none() {
            return new RevetStatus(false, List.of());
        }
    }

    private SupportGroupConfig require(String igAccount) {
        return configs.findByIgAccount(igAccount)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no config for " + igAccount));
    }
}

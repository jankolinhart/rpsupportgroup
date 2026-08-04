package com.reelypops.rpsupportgroup.group;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal service-to-service SG config surface on {@code /supportgroup/v1/internal/groups}, authenticated by the
 * shared {@code X-Internal-Api-Key} (SecurityConfig internal chain). The scanner reads a group's authoritative
 * definition here to segment rounds the same way the client does; the admin BFF lists configs for the console; and
 * the rpserver BFF forwards a client's 3b upload here to auto-register a new config as UNCLAIMED.
 */
@RestController
@RequestMapping("/supportgroup/v1/internal/groups")
public class InternalGroupController {

    private final SupportGroupConfigService service;
    private final SupportGroupAvatarService avatarService;
    private final SgCategoryService categoryService;

    public InternalGroupController(SupportGroupConfigService service, SupportGroupAvatarService avatarService,
                                   SgCategoryService categoryService) {
        this.service = service;
        this.avatarService = avatarService;
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<GroupResponse> list() {
        List<SupportGroupConfig> cs = service.list();
        Map<UUID, SupportGroupConfigService.RevetStatus> revet = service.revetStatuses(cs);
        return cs.stream()
                .map(c -> {
                    SupportGroupConfigService.RevetStatus s = revet.get(c.getId());
                    return GroupResponse.of(c, List.of(), s.needsRevet(), s.reasons());
                })
                .toList();
    }

    /**
     * The client's public browse (Cycle 12) via the rpserver BFF: a page of VETTED configs, optionally filtered by
     * any of the given category slugs (OR) and a case-insensitive substring on the group's IG account. Distinct from
     * {@link #list()} (which returns every config, incl. pending, for the admin console).
     */
    @GetMapping("/browse")
    public PagedGroups browse(
            @RequestParam(name = "categories", required = false) List<String> categories,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "24") int size) {
        return PagedGroups.of(service.browse(categories, q, page, size));
    }

    @GetMapping("/{igAccount}")
    public GroupResponse get(@PathVariable String igAccount) {
        SupportGroupConfig c = service.get(igAccount);
        SupportGroupConfigService.RevetStatus s = service.revetStatus(c);
        return GroupResponse.of(c, service.activeChangeNote(c), s.needsRevet(), s.reasons());
    }

    /** The BFF forwards a client's 3b upload here: auto-register the config as UNCLAIMED (§6). */
    @PostMapping
    public ResponseEntity<GroupResponse> create(@Valid @RequestBody CreateGroupRequest req) {
        var result = service.intake(req.igAccount(), req.definition(), req.description());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(GroupResponse.of(result.config()));
    }

    /** Admin override (Cycle 9): attribute an unclaimed config to a ReelyPops user without the claim flow. */
    @PostMapping("/{igAccount}/attribute")
    public GroupResponse attribute(@PathVariable String igAccount, @Valid @RequestBody AttributeRequest req) {
        return GroupResponse.of(service.attribute(igAccount, req.ownerId()));
    }

    /** Operator vetting (Cycle 10): approve a config so it becomes publicly browsable and the creator's copy locks. */
    @PostMapping("/{igAccount}/vet")
    public GroupResponse vet(@PathVariable String igAccount) {
        return GroupResponse.of(service.vet(igAccount));
    }

    /** Operator soft-reject (P1): a re-requestable-after-cooldown decision (reason + cooldown days). */
    @PostMapping("/{igAccount}/reject")
    public GroupResponse reject(@PathVariable String igAccount, @Valid @RequestBody RejectRequest req) {
        return GroupResponse.of(service.reject(igAccount, req.reason(), req.cooldownDays()));
    }

    /** Operator block (P1): the terminal, admin-only abuse verdict. */
    @PostMapping("/{igAccount}/block")
    public GroupResponse block(@PathVariable String igAccount) {
        return GroupResponse.of(service.block(igAccount));
    }

    /**
     * Operator correction (Cycle 10/11): replace the authoritative definition (fix timings / timezone / opening
     * weekdays / marker owners) and the optional group description in one call.
     */
    @PutMapping("/{igAccount}")
    public GroupResponse updateConfig(@PathVariable String igAccount, @Valid @RequestBody UpdateGroupRequest req) {
        return GroupResponse.of(service.updateConfig(igAccount, req.definition(), req.description()));
    }

    /**
     * Vetting Portal "Save" (M3a): persist the single authoritative {@link VettedProfile} (§6) — a pre-vetting
     * correction that projects the round-truth onto the legacy definition but does <strong>not</strong> vet.
     */
    @PutMapping("/{igAccount}/vetted-profile")
    public GroupResponse saveVettedProfile(@PathVariable String igAccount, @Valid @RequestBody VettedProfile profile) {
        return GroupResponse.of(service.saveVettedProfile(igAccount, profile));
    }

    /** Vetting Portal "Vet Now" (M3a): save the vetted profile <strong>and</strong> flip UNDER_VERIFICATION → VETTED. */
    @PostMapping("/{igAccount}/vetted-profile/vet")
    public GroupResponse vetVettedProfile(@PathVariable String igAccount, @Valid @RequestBody VettedProfile profile) {
        return GroupResponse.of(service.vetVettedProfile(igAccount, profile));
    }

    /** The admin-only detected + vetted profile pair the Vetting Portal renders + diffs (M3a). */
    @GetMapping("/{igAccount}/vetting-profiles")
    public VettingProfilesResponse vettingProfiles(@PathVariable String igAccount) {
        return service.getVettingProfiles(igAccount);
    }

    /** Admin: remove a config. */
    @DeleteMapping("/{igAccount}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String igAccount) {
        service.remove(igAccount);
    }

    /** Admin: assign a content category to a group (Cycle 12) — idempotent. */
    @PutMapping("/{igAccount}/categories/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignCategory(@PathVariable String igAccount, @PathVariable String slug) {
        categoryService.assign(igAccount, slug);
    }

    /** Admin: unassign a content category from a group (Cycle 12) — idempotent. */
    @DeleteMapping("/{igAccount}/categories/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassignCategory(@PathVariable String igAccount, @PathVariable String slug) {
        categoryService.unassign(igAccount, slug);
    }

    /**
     * Client-contributed SG avatar (Cycle 10): the client fetches the real profile image via its residential exit
     * and uploads the bytes here through the BFF. Upserted per group and served back at {@code GET …/avatar} at a
     * URL templatable by the group's Instagram account.
     */
    @PutMapping("/{igAccount}/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putAvatar(@PathVariable String igAccount,
                          @RequestHeader(value = "Content-Type", required = false) String contentType,
                          @RequestBody(required = false) byte[] image) {
        avatarService.put(igAccount, image, contentType);
    }

    /** Serve a group's stored avatar — 404 when none has been contributed yet (the client then shows a placeholder). */
    @GetMapping("/{igAccount}/avatar")
    public ResponseEntity<byte[]> getAvatar(@PathVariable String igAccount) {
        return avatarService.get(igAccount)
                .map(a -> ResponseEntity.ok().contentType(MediaType.parseMediaType(a.getContentType())).body(a.getImage()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Admin: the config's append-only vetted-profile version history, newest first (M5 A2). */
    @GetMapping("/{igAccount}/vetted-profile/versions")
    public GroupVersionsResponse versions(@PathVariable String igAccount) {
        return GroupVersionsResponse.of(service.listVersions(igAccount));
    }

    /** Admin roll back / activate a prior version (M5 A2 kill switch): repoint the active snapshot. 404 if it does not exist. */
    @PostMapping("/{igAccount}/vetted-profile/rollback/{snapshotVersion}")
    public GroupResponse rollback(@PathVariable String igAccount, @PathVariable long snapshotVersion) {
        return GroupResponse.of(service.rollbackTo(igAccount, snapshotVersion));
    }

    /** Admin flip the operational mode (M5 A2 kill switch): liking / scrape-only / paused. */
    @PutMapping("/{igAccount}/mode/{mode}")
    public GroupResponse setMode(@PathVariable String igAccount, @PathVariable SgConfigMode mode) {
        return GroupResponse.of(service.setMode(igAccount, mode));
    }

    /**
     * M5 re-vet consumer: rpenduser forwards one client-reported drift observation here (a persistent marker-disagree
     * tally or a new-owner nomination). Upserted per reporter; an unresolved marker-disagree observation derives the
     * config's "needs re-vet" flag. Returns 202 — the config is unchanged (a read-side signal, no ETag bump).
     */
    @PostMapping("/{igAccount}/drift")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void reportDrift(@PathVariable String igAccount, @Valid @RequestBody DriftReport req) {
        service.recordDrift(igAccount, req.kind(), req.reporterDeviceId(), req.reporterUserId(),
                req.nominatedOwnerHandle(), req.agreePass(), req.disagreePass(), req.persistenceCount());
    }

    /**
     * M5 re-vet consumer admin "acknowledge" (I reviewed, it's fine — a non-severe drift): resolve the config's open
     * marker-disagree observations WITHOUT a re-vet, clearing the derived needs-re-vet flag. No config mutation / no
     * ETag bump; a fresh drift re-raises it. Returns the config (now {@code needsRevet=false}).
     */
    @PostMapping("/{igAccount}/drift/acknowledge")
    public GroupResponse acknowledgeRevet(@PathVariable String igAccount) {
        SupportGroupConfig c = service.acknowledgeRevet(igAccount);
        SupportGroupConfigService.RevetStatus s = service.revetStatus(c);
        return GroupResponse.of(c, service.activeChangeNote(c), s.needsRevet(), s.reasons());
    }

    /** Admin: a config's open new-owner nominations (M5 review-candidate surface), newest-seen first. */
    @GetMapping("/{igAccount}/nominations")
    public List<DriftObservationResponse> nominations(@PathVariable String igAccount) {
        return service.newOwnerNominations(igAccount).stream().map(DriftObservationResponse::of).toList();
    }

    /**
     * Admin "Add" (M5.20): confirm a new-owner nomination — add the handle to the config's vetted marker owners
     * (idempotent; bumps the version so the client adopts it on its next poll) and drop the handle off the review
     * list. Returns the updated config with its recomputed re-vet status.
     */
    @PostMapping("/{igAccount}/nominations/{handle}/confirm")
    public GroupResponse confirmNomination(@PathVariable String igAccount, @PathVariable String handle) {
        SupportGroupConfig c = service.confirmNomination(igAccount, handle);
        SupportGroupConfigService.RevetStatus s = service.revetStatus(c);
        return GroupResponse.of(c, service.activeChangeNote(c), s.needsRevet(), s.reasons());
    }

    /**
     * Admin "Dismiss" (M5.20): reject a new-owner nomination (not a marker owner) — drop the handle off the review
     * list without changing the owner set or the config version. A later re-nomination re-raises it. Returns the
     * (unchanged) config with its recomputed re-vet status.
     */
    @PostMapping("/{igAccount}/nominations/{handle}/dismiss")
    public GroupResponse dismissNomination(@PathVariable String igAccount, @PathVariable String handle) {
        SupportGroupConfig c = service.dismissNomination(igAccount, handle);
        SupportGroupConfigService.RevetStatus s = service.revetStatus(c);
        return GroupResponse.of(c, service.activeChangeNote(c), s.needsRevet(), s.reasons());
    }
}

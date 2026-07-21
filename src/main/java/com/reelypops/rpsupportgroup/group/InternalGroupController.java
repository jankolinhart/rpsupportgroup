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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
        return service.list().stream().map(GroupResponse::of).toList();
    }

    @GetMapping("/{igAccount}")
    public GroupResponse get(@PathVariable String igAccount) {
        return GroupResponse.of(service.get(igAccount));
    }

    /** The BFF forwards a client's 3b upload here: auto-register the config as UNCLAIMED (§6). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse create(@Valid @RequestBody CreateGroupRequest req) {
        return GroupResponse.of(service.create(req.igAccount(), req.definition()));
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

    /** Operator correction (Cycle 10): replace the authoritative definition (fix timings / timezone / marker owners). */
    @PutMapping("/{igAccount}")
    public GroupResponse updateDefinition(@PathVariable String igAccount, @Valid @RequestBody GroupDefinition definition) {
        return GroupResponse.of(service.updateDefinition(igAccount, definition));
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
}

package com.reelypops.rpsupportgroup.group;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * SG config API on {@code /supportgroup/v1/groups} for the authenticated ReelyPops client (JWT). A client can
 * register a new unclaimed config (§6), read a config by its Instagram account, and list configs. The scanner
 * and admin BFF read configs on the internal surface ({@link InternalGroupController}).
 */
@RestController
@RequestMapping("/supportgroup/v1/groups")
public class SupportGroupConfigController {

    private final SupportGroupConfigService service;

    public SupportGroupConfigController(SupportGroupConfigService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse create(@Valid @RequestBody CreateGroupRequest req) {
        return GroupResponse.of(service.create(req.igAccount(), req.definition(), req.description()));
    }

    /**
     * The public browse list (Cycle 10/12): a page of VETTED configs for “choose a support group”, optionally
     * filtered by any of the given category slugs (OR) and a case-insensitive substring on the group's IG account.
     */
    @GetMapping
    public PagedGroups browse(
            @RequestParam(name = "categories", required = false) List<String> categories,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "24") int size) {
        return PagedGroups.of(service.browse(categories, q, page, size));
    }

    @GetMapping("/{igAccount}")
    public GroupResponse get(@PathVariable String igAccount) {
        return GroupResponse.of(service.get(igAccount));
    }

    /** A subscribed SG owner (ROLE_SG_ADMIN, gated in SecurityConfig) claims an unclaimed config (§6). */
    @PostMapping("/{igAccount}/claim")
    public GroupResponse claim(@AuthenticationPrincipal Jwt jwt, @PathVariable String igAccount) {
        return GroupResponse.of(service.claim(igAccount, UUID.fromString(jwt.getSubject())));
    }

    /** A client reports a discovered marker owner (Q4) — authenticated, idempotent, no reporter identity stored. */
    @PostMapping("/{igAccount}/marker-owners")
    public GroupResponse addMarkerOwner(@PathVariable String igAccount, @Valid @RequestBody MarkerOwnerRequest req) {
        return GroupResponse.of(service.addMarkerOwner(igAccount, req.handle()));
    }

    /** The owner (ROLE_SG_ADMIN + ownership, checked in the service) revokes a marker owner (Q4 safety-net). */
    @DeleteMapping("/{igAccount}/marker-owners/{handle}")
    public GroupResponse removeMarkerOwner(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable String igAccount, @PathVariable String handle) {
        return GroupResponse.of(service.removeMarkerOwner(igAccount, handle, UUID.fromString(jwt.getSubject())));
    }
}

package com.reelypops.rpsupportgroup.group;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public InternalGroupController(SupportGroupConfigService service) {
        this.service = service;
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
}

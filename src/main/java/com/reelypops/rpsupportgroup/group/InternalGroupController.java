package com.reelypops.rpsupportgroup.group;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal service-to-service SG config reads on {@code /supportgroup/v1/internal/groups}, authenticated by the
 * shared {@code X-Internal-Api-Key} (SecurityConfig internal chain). The scanner reads a group's authoritative
 * definition here to segment rounds the same way the client does; the admin BFF lists configs for the console.
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
}

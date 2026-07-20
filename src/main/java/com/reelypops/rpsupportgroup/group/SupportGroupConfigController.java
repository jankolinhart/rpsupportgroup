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
        return GroupResponse.of(service.create(req.igAccount(), req.definition()));
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

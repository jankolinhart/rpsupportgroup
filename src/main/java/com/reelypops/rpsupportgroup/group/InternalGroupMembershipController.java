package com.reelypops.rpsupportgroup.group;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The membership registry read GROUP-FIRST, on
 * {@code /supportgroup/v1/internal/groups/{igAccount}/memberships} — authenticated by the shared
 * {@code X-Internal-Api-Key} like every other internal surface here. Never client-facing.
 *
 * <p>Everything else in this registry is user-first: given a customer, what do they hold. The admin
 * deep-scrape trigger needs the inverse, because a scrape duty must be addressed to a machine that actually
 * runs the group — a duty sent anywhere else is silently ignored by the client that receives it, so the
 * operator would be offered a button that does nothing.</p>
 *
 * <p>This returns WHO holds the group, as (user, handle) pairs. It deliberately says nothing about machines:
 * this registry has never known which device a handle sits on, and inventing that link here would put a fact
 * in the wrong service. The caller joins it to rpenduser, which does know.</p>
 */
@RestController
@RequestMapping("/supportgroup/v1/internal/groups/{igAccount}/memberships")
public class InternalGroupMembershipController {

    private final SgMembershipService service;

    public InternalGroupMembershipController(SgMembershipService service) {
        this.service = service;
    }

    /** Every live membership of this group (200, empty array when nobody holds it). */
    @GetMapping
    public List<GroupMembershipResponse> list(@PathVariable String igAccount) {
        return service.byIgAccount(igAccount).stream().map(GroupMembershipResponse::of).toList();
    }
}

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
import java.util.UUID;

/**
 * Internal east-west membership surface (B6) on {@code /supportgroup/v1/internal/users/{userId}/memberships},
 * authenticated by the shared {@code X-Internal-Api-Key} (SecurityConfig internal chain). rpenduser WRITES a batch
 * of the client's observed follow statuses (each element idempotently upserted through {@link SgMembershipService})
 * and READS the user's rows back to decide suppression. Never client-facing.
 */
@RestController
@RequestMapping("/supportgroup/v1/internal/users/{userId}/memberships")
public class InternalMembershipController {

    private final SgMembershipService service;

    public InternalMembershipController(SgMembershipService service) {
        this.service = service;
    }

    /**
     * Idempotent batch WRITE. Applies each element through the service; elements NOT present are never touched
     * (absence != unfollow). Returns 204.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@PathVariable UUID userId, @Valid @RequestBody List<@Valid MembershipReport> reports) {
        reports.forEach(r -> service.report(userId, r.igHandle(), r.igAccount(), r.followingStatus()));
    }

    /** READ every membership row for the user (200, empty array when none). */
    @GetMapping
    public List<MembershipResponse> list(@PathVariable UUID userId) {
        return service.byUser(userId).stream().map(MembershipResponse::of).toList();
    }
}

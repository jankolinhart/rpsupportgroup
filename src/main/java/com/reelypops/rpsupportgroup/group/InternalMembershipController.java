package com.reelypops.rpsupportgroup.group;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Internal east-west membership surface (B6) on {@code /supportgroup/v1/internal/users/{userId}/memberships},
 * authenticated by the shared {@code X-Internal-Api-Key} (SecurityConfig internal chain). rpenduser WRITES a batch
 * of the client's observed follow statuses (each element idempotently upserted through {@link SgMembershipService})
 * and READS the user's rows back to decide suppression. rpserver CLAIMS one row at a time through
 * {@code POST /claim} (behind its {@code groups.max} gate) and RELEASES one through the DELETE. Never
 * client-facing.
 *
 * <p><b>The claim has its own route, and that is deliberate.</b> It used to be posted as a one-element batch to
 * the report endpoint, which made the two indistinguishable to everything downstream — and they are not the same
 * thing. A report is what a desktop client SAW, replayed out of a device report; a claim is what the USER DID,
 * authenticated and admitted by a quota gate. Only the second is allowed to lift the tombstone a release leaves
 * behind, so the routes are separate at the wire, not merely by a flag some future caller could set.</p>
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

    /**
     * CLAIM one membership — the explicit, quota-gated join, and the mirror of the DELETE below.
     *
     * <p>Behaves like a {@code following} report in every visible way (idempotent upsert on the natural key, 204
     * either way) with ONE difference that is the reason it exists: it clears any standing release for that
     * triple, so a user who gave a group back and has now re-joined it gets their membership restored. The report
     * route cannot do that — if an observation could clear the mark, a removal would still be undone by the next
     * device report, which is the bug this route was carved out to fix.</p>
     */
    @PostMapping("/claim")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void claim(@PathVariable UUID userId, @Valid @RequestBody MembershipClaim claim) {
        service.claim(userId, claim.igHandle(), claim.igAccount());
    }

    /**
     * RELEASE one membership — the DELETE half of the claim, and the only route that removes a registry row.
     * Scoped to the natural key: {@code igHandle} + {@code igAccount} name exactly which row goes, so every other
     * handle's row survives.
     *
     * <p><strong>Idempotent: 204 whether or not there was anything to release.</strong> A membership that is
     * already gone is the state the caller asked for, and rpserver's client retries this call — answering 404
     * would turn a successful retry into an error the user is shown for a group they already got rid of.
     *
     * <p>The two names are QUERY PARAMETERS rather than a request body. A DELETE body is stripped or ignored by
     * enough of the stack (proxies, some HTTP clients) that it is not a safe place to put the half of the key
     * that decides WHICH row is deleted — a silently dropped body would be a delete aimed at the wrong row or at
     * nothing. Blank is refused rather than normalised away, so a caller can never delete by accident.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID userId,
                        @RequestParam String igHandle,
                        @RequestParam String igAccount) {
        if (igHandle.isBlank() || igAccount.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "igHandle and igAccount are both required to identify the membership to release");
        }
        service.release(userId, igHandle, igAccount);
    }

    /** READ every membership row for the user (200, empty array when none). */
    @GetMapping
    public List<MembershipResponse> list(@PathVariable UUID userId) {
        return service.byUser(userId).stream().map(MembershipResponse::of).toList();
    }
}

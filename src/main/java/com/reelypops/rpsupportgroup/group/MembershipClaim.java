package com.reelypops.rpsupportgroup.group;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of the CLAIM route (B6): rpserver naming the one membership its {@code groups.max} gate has just
 * admitted for the token's user.
 *
 * <p>There is no {@code followingStatus} here, and its absence is the point. A claim is not an observation with a
 * value attached — it IS the assertion that this handle is in this group, made by the user through an
 * authenticated, quota-gated route. Carrying a status field would let a claim be written as though it were one
 * more element of a device report, which is exactly the conflation that let an observation quietly undo a
 * removal. Both handles are required: they are the natural key.</p>
 */
public record MembershipClaim(
        @NotBlank String igHandle,
        @NotBlank String igAccount) {
}

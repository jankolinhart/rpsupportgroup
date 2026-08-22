package com.reelypops.rpsupportgroup.group;

import java.time.Instant;

/**
 * One membership row as returned by the internal READ (B6): the IG account pair, the stored status, and the two
 * display timestamps. Null timestamps are omitted by the service-wide {@code non_null} Jackson inclusion.
 */
public record MembershipResponse(
        String igHandle,
        String igAccount,
        String status,
        Instant followingConfirmedAt,
        Instant instantiatedAt) {

    static MembershipResponse of(SgMembership m) {
        return new MembershipResponse(m.getIgHandle(), m.getIgAccount(), m.getStatus().name(),
                m.getFollowingConfirmedAt(), m.getInstantiatedAt());
    }
}

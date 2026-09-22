package com.reelypops.rpsupportgroup.group;

import java.util.UUID;

/**
 * One holder of a support group: which customer, and on which Instagram handle.
 *
 * <p>The mirror of {@code MembershipResponse}, which answers the user-first question and therefore does not
 * carry the user. This one does, because the question it answers is "who".</p>
 */
public record GroupMembershipResponse(UUID userId, String igHandle, String igAccount, String status) {

    static GroupMembershipResponse of(SgMembership m) {
        return new GroupMembershipResponse(m.getUserId(), m.getIgHandle(), m.getIgAccount(),
                m.getStatus() == null ? null : m.getStatus().name());
    }
}

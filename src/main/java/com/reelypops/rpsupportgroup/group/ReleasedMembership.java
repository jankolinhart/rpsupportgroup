package com.reelypops.rpsupportgroup.group;

import java.time.Instant;

/**
 * One membership a user has given back and not re-taken: the pair, and when they did it.
 *
 * <p>The PAIR is the whole of it. A membership is {@code (igHandle, igAccount)} — one of the customer's own
 * accounts joined to one group — so a client that acted on the group alone would remove every account's
 * connection to it, including ones the user never released.
 *
 * <p>{@code releasedAt} is carried because a client has to be able to explain itself. Removing something
 * quietly is right — the user already decided — but the notice that follows says WHEN, and a client that
 * had to guess would say "just now" about a decision made last Tuesday.
 */
public record ReleasedMembership(String igHandle, String igAccount, Instant releasedAt) {

    static ReleasedMembership of(SgMembershipTombstone tombstone) {
        return new ReleasedMembership(tombstone.getIgHandle(), tombstone.getIgAccount(),
                tombstone.getReleasedAt());
    }
}

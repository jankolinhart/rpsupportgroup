package com.reelypops.rpsupportgroup.group;

import jakarta.validation.constraints.NotBlank;

/**
 * One element of the WRITE array (B6) forwarded by rpenduser: a client's observed follow status of a single IG
 * account. {@code followingStatus} is the raw signal — {@code "following"} confirms, {@code "not_following"} flips,
 * and {@code "unknown"}/{@code "requested"}/blank/{@code null} are fail-open no-ops — so it is intentionally
 * unconstrained (blank / absent is a valid inconclusive report). The handles are required.
 */
public record MembershipReport(
        @NotBlank String igHandle,
        @NotBlank String igAccount,
        String followingStatus) {
}

package com.reelypops.rpsupportgroup.group;

import jakarta.validation.constraints.NotBlank;

/** Payload to register a client-discovered marker owner (Q4): the IG handle whose marker posts delimit rounds. */
public record MarkerOwnerRequest(@NotBlank String handle) {
}

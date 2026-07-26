package com.reelypops.rpsupportgroup.vetting;

import java.util.UUID;

/** The result of storing an operator-uploaded marker image: its id (for serving) and computed dHash (for matching). */
public record MarkerImageResponse(UUID id, String dHash) {

    public static MarkerImageResponse of(UploadedMarkerImage image) {
        return new MarkerImageResponse(image.getId(), image.getDHash());
    }
}

package com.reelypops.rpsupportgroup.vetting;

import java.util.UUID;

/**
 * The result of storing an operator-uploaded marker image: its id (for serving), the computed dHash (for matching), and
 * the auto-OCR'd overlay {@code ocrText} (for a {@code TEXT_OVERLAY} marker), or {@code null} when the gateway is
 * off/unavailable or the image has no readable text (the operator types it in).
 */
public record MarkerImageResponse(UUID id, String dHash, String ocrText) {

    public static MarkerImageResponse of(UploadedMarkerImage image, String ocrText) {
        return new MarkerImageResponse(image.getId(), image.getDHash(), ocrText);
    }
}

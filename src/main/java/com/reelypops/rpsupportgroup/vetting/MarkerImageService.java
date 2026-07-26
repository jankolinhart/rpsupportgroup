package com.reelypops.rpsupportgroup.vetting;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Stores operator-uploaded marker images and computes their best-effort perceptual hash (M3 follow-up). Used from the
 * internal vetting surface when the Vetting Portal operator supplies a marker the auto-scrape missed.
 */
@Service
public class MarkerImageService {

    private static final String DEFAULT_CONTENT_TYPE = "image/jpeg";

    private final UploadedMarkerImageRepository images;

    public MarkerImageService(UploadedMarkerImageRepository images) {
        this.images = images;
    }

    /** Hash + store an uploaded marker image (400 when no bytes / not a decodable image). */
    @Transactional
    public UploadedMarkerImage upload(byte[] image, String contentType) {
        if (image == null || image.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "marker image is required");
        }
        String dHash = ImageDHash.hash(image);
        String type = (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        return images.save(UploadedMarkerImage.create(dHash, image, type));
    }

    /** The stored marker image, or empty when the id is unknown. */
    @Transactional(readOnly = true)
    public Optional<UploadedMarkerImage> get(UUID id) {
        return images.findById(id);
    }
}

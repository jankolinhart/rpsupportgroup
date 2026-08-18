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
 *
 * <p><strong>⚠️ THIS IS THE LAST PLACE THE CLOUD MINTS A FINGERPRINT, AND IT IS KNOWN TO BE THE WRONG DIALECT.</strong>
 * Measured 16/08/2026: {@link ImageDHash} (ImageIO/AWT) and the desktop client's {@code lib/dhash.js}
 * (sharp/libvips) land <strong>15–34 bits apart</strong> on identical bytes, while clients accept a match at 4–10.
 * A hash minted here is therefore well-formed, plausible, and unmatchable by the clients that have to use it.
 * Every other path — deep-scrape corpus, drift reports, repair, adoption — now carries the CLIENT's value verbatim;
 * an upload is the one case where no client value exists, because no client has ever seen the picture.
 *
 * <p><strong>Prefer a client-contributed candidate wherever one exists</strong> (the deep-scrape corpus, or the
 * durable client-marker-image catalogue a drift report files). Upload only as a last resort, and treat the
 * resulting reference as provisional until a client confirms it matches something.</p>
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

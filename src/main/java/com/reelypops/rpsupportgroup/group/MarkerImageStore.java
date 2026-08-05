package com.reelypops.rpsupportgroup.group;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Captures + serves durable, <strong>content-addressed</strong> marker DISPLAY images (sg-per-weekday-marker-images.md).
 * At vet time a marker reference's chosen image is reduced to a small JPEG thumbnail, keyed by the sha-256 hex of the
 * thumbnail bytes (= its {@code imageLocator}) and stored once (identical images dedupe). The desktop client fetches by
 * locator and caches by content — an unchanged image is never re-fetched; a changed image yields a new locator.
 */
@Service
public class MarkerImageStore {

    /** Longest-side cap of the stored display thumbnail (px). */
    static final int MAX_DIM = 256;
    static final String CONTENT_TYPE = "image/jpeg";
    private static final String ALGORITHM = "SHA-256";

    private final MarkerImageRepository images;

    public MarkerImageStore(MarkerImageRepository images) {
        this.images = images;
    }

    /**
     * Reduce {@code source} to a durable display thumbnail and return its content-hash locator. Empty when the bytes are
     * not a decodable image (the reference then simply carries no {@code imageLocator}). Idempotent — an identical image
     * resolves to the same locator and is stored once.
     */
    @Transactional
    public Optional<String> capture(byte[] source) {
        return thumbnail(source).map(thumb -> {
            String locator = hashHex(thumb, ALGORITHM);
            if (!images.existsById(locator)) {
                images.save(MarkerImage.create(locator, thumb, CONTENT_TYPE));
            }
            return locator;
        });
    }

    /** The stored image for a locator, or empty when the locator is blank or unknown. */
    @Transactional(readOnly = true)
    public Optional<MarkerImage> find(String locator) {
        return locator == null || locator.isBlank() ? Optional.empty() : images.findById(locator);
    }

    /**
     * A JPEG thumbnail of {@code source} (longest side &le; {@link #MAX_DIM}, aspect preserved), or empty when the bytes
     * are not a decodable image (unrecognised or truncated/corrupt). Shares one {@code try} so the decode + encode I/O
     * paths fold into a single failure branch.
     */
    static Optional<byte[]> thumbnail(byte[] source) {
        if (source == null || source.length == 0) {
            return Optional.empty();
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(source));
            if (img == null) {
                return Optional.empty();
            }
            double scale = Math.min(1.0, (double) MAX_DIM / Math.max(img.getWidth(), img.getHeight()));
            int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
            BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(thumb, "jpg", out);
            return Optional.of(out.toByteArray());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Lowercase hex of the {@code algorithm} digest of {@code bytes}. {@code algorithm} is a seam (production uses
     * {@code SHA-256}) so the "unavailable algorithm" branch is exercisable.
     */
    static String hashHex(byte[] bytes, String algorithm) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("hash algorithm unavailable: " + algorithm, e);
        }
    }
}

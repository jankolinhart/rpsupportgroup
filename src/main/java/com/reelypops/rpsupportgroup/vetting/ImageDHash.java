package com.reelypops.rpsupportgroup.vetting;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Best-effort server-side perceptual dHash for an operator-uploaded marker image (M3 follow-up).
 *
 * <p>Emits the SAME 64-character '0'/'1' format the scraper produces in {@code playwright-scripts/lib/dhash.js}:
 * greyscale &rarr; resize to 9&times;8 &rarr; horizontal gradient (each of the 8 rows yields 8 bits, '1' when a pixel
 * is darker than its right neighbour). Because Java's {@link ImageIO}/AWT resize kernel differs from sharp/libvips,
 * a hash computed here is not guaranteed bit-identical to the scraper's for the same source image — it is a
 * best-effort match, adequate for storing an upload's reference hash alongside detected clusters. See
 * {@code docs/analysis/marker-detection-hierarchy.md}.
 */
public final class ImageDHash {

    private static final int W = 9;
    private static final int H = 8;

    private ImageDHash() {
    }

    /**
     * The 64-char dHash bit-string of an image. 400 (bad request) when the bytes are not a decodable image.
     */
    public static String hash(byte[] imageBytes) {
        BufferedImage source = decode(imageBytes);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported or corrupt image");
        }
        int[] gray = greyscale9x8(source);
        StringBuilder bits = new StringBuilder(H * (W - 1));
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W - 1; x++) {
                bits.append(gray[y * W + x] < gray[y * W + x + 1] ? '1' : '0');
            }
        }
        return bits.toString();
    }

    /** Decode image bytes, treating both an unrecognised format and a corrupt stream as "no image" (null). */
    private static BufferedImage decode(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            return null;
        }
    }

    /** Scale to 9&times;8 (bilinear) and return the row-major Rec.601 luma of each pixel (0-255). */
    private static int[] greyscale9x8(BufferedImage source) {
        BufferedImage small = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, W, H, null);
        g.dispose();
        int[] gray = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb = small.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int gc = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                gray[y * W + x] = (int) Math.round(0.299 * r + 0.587 * gc + 0.114 * b);
            }
        }
        return gray;
    }
}

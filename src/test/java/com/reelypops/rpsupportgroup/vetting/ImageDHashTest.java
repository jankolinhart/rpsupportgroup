package com.reelypops.rpsupportgroup.vetting;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for the best-effort server-side dHash. Verifies the 64-bit format and the horizontal-gradient semantics
 * (a bit is '1' when a pixel is darker than its right neighbour), plus rejection of undecodable input: empty, null,
 * unrecognised garbage, and a truncated-but-recognised image (the corrupt-stream path).
 */
class ImageDHashTest {

    /** A w&times;h PNG whose brightness ramps horizontally; each row is strictly monotonic. */
    private static byte[] horizontalRamp(int w, int h, boolean brightToRight) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (int) Math.round(255.0 * x / (w - 1));
                if (!brightToRight) {
                    v = 255 - v;
                }
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void hashOfAnImageIs64Bits() throws IOException {
        assertThat(ImageDHash.hash(horizontalRamp(90, 80, true))).hasSize(64).matches("[01]{64}");
    }

    @Test
    void brightnessIncreasingLeftToRightIsAllOnes() throws IOException {
        // every pixel is darker than its right neighbour => every bit is '1'
        assertThat(ImageDHash.hash(horizontalRamp(90, 80, true))).isEqualTo("1".repeat(64));
    }

    @Test
    void brightnessDecreasingLeftToRightIsAllZeros() throws IOException {
        assertThat(ImageDHash.hash(horizontalRamp(90, 80, false))).isEqualTo("0".repeat(64));
    }

    @Test
    void nullBytesRejected() {
        assertThatThrownBy(() -> ImageDHash.hash(null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void emptyBytesRejected() {
        assertThatThrownBy(() -> ImageDHash.hash(new byte[0])).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unrecognisedBytesRejected() {
        assertThatThrownBy(() -> ImageDHash.hash("not an image".getBytes()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void truncatedImageRejected() throws IOException {
        byte[] png = horizontalRamp(90, 80, true);
        byte[] truncated = Arrays.copyOf(png, png.length / 2);
        assertThatThrownBy(() -> ImageDHash.hash(truncated)).isInstanceOf(ResponseStatusException.class);
    }
}

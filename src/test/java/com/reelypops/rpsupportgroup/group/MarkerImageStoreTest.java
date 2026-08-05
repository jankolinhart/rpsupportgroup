package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit coverage for the content-addressed marker display-image store: JPEG thumbnailing, sha-256 locator, dedup + fetch. */
class MarkerImageStoreTest {

    private final MarkerImageRepository images = mock(MarkerImageRepository.class);
    private final MarkerImageStore store = new MarkerImageStore(images);

    /** A w×h PNG with per-pixel variation (a decodable real image). */
    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, (x * 7 + y * 13) & 0xffffff);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // --- thumbnail ---

    @Test
    void thumbnailCapsTheLongestSideTo256AndStaysDecodable() throws IOException {
        Optional<byte[]> thumb = MarkerImageStore.thumbnail(png(600, 400));
        assertThat(thumb).isPresent();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumb.get()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(256);   // 600×400 → 256×171
        assertThat(decoded.getHeight()).isEqualTo(171);
    }

    @Test
    void thumbnailLeavesASmallImageUnscaled() throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(MarkerImageStore.thumbnail(png(40, 30)).orElseThrow()));
        assertThat(decoded.getWidth()).isEqualTo(40);
        assertThat(decoded.getHeight()).isEqualTo(30);
    }

    @Test
    void thumbnailOfNullEmptyGarbageOrTruncatedIsEmpty() throws IOException {
        assertThat(MarkerImageStore.thumbnail(null)).isEmpty();
        assertThat(MarkerImageStore.thumbnail(new byte[0])).isEmpty();
        assertThat(MarkerImageStore.thumbnail("not an image".getBytes())).isEmpty(); // ImageIO.read → null
        byte[] png = png(80, 60);
        assertThat(MarkerImageStore.thumbnail(Arrays.copyOf(png, png.length / 2))).isEmpty(); // decode IOException
    }

    // --- hashHex ---

    @Test
    void hashHexIsADeterministic64CharLowercaseSha256() {
        String a = MarkerImageStore.hashHex(new byte[]{1, 2, 3}, "SHA-256");
        assertThat(a).isEqualTo(MarkerImageStore.hashHex(new byte[]{1, 2, 3}, "SHA-256"))
                .hasSize(64).matches("[0-9a-f]{64}");
        assertThat(MarkerImageStore.hashHex(new byte[]{9}, "SHA-256")).isNotEqualTo(a);
    }

    @Test
    void hashHexThrowsForAnUnavailableAlgorithm() {
        assertThatThrownBy(() -> MarkerImageStore.hashHex(new byte[]{1}, "NO-SUCH-ALG"))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- capture ---

    @Test
    void captureStoresANewThumbnailUnderItsLocator() throws IOException {
        when(images.existsById(any())).thenReturn(false);
        Optional<String> locator = store.capture(png(300, 300));
        assertThat(locator).isPresent();
        assertThat(locator.get()).hasSize(64);
        verify(images).save(any(MarkerImage.class));
    }

    @Test
    void captureSkipsTheSaveWhenTheImageIsAlreadyStored() throws IOException {
        when(images.existsById(any())).thenReturn(true);
        assertThat(store.capture(png(300, 300))).isPresent();
        verify(images, never()).save(any());
    }

    @Test
    void captureOfAnUndecodableSourceIsEmptyAndStoresNothing() {
        assertThat(store.capture("nope".getBytes())).isEmpty();
        verify(images, never()).save(any());
    }

    // --- find ---

    @Test
    void findShortCircuitsForABlankLocator() {
        assertThat(store.find(null)).isEmpty();
        assertThat(store.find("  ")).isEmpty();
        verifyNoInteractions(images);
    }

    @Test
    void findDelegatesToTheRepositoryForARealLocator() {
        MarkerImage img = MarkerImage.create("loc", new byte[]{1}, "image/jpeg");
        when(images.findById("loc")).thenReturn(Optional.of(img));
        assertThat(store.find("loc")).contains(img);
        assertThat(store.find("unknown")).isEmpty();
    }
}

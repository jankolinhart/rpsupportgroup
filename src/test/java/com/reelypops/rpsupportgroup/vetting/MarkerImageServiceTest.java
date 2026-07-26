package com.reelypops.rpsupportgroup.vetting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test (service in isolation, repository mocked) for uploaded marker images: the empty/null guard, the
 * content-type fallback, that a perceptual hash is computed + stored, and the get passthrough.
 */
@ExtendWith(MockitoExtension.class)
class MarkerImageServiceTest {

    @Mock
    UploadedMarkerImageRepository images;

    @InjectMocks
    MarkerImageService service;

    private static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(90, 80, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 90; x++) {
                int v = (int) Math.round(255.0 * x / 89);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void uploadHashesAndStores() throws IOException {
        when(images.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UploadedMarkerImage saved = service.upload(png(), "image/png");

        ArgumentCaptor<UploadedMarkerImage> captor = ArgumentCaptor.forClass(UploadedMarkerImage.class);
        verify(images).save(captor.capture());
        UploadedMarkerImage stored = captor.getValue();
        assertThat(stored.getDHash()).isEqualTo("1".repeat(64));
        assertThat(stored.getContentType()).isEqualTo("image/png");
        assertThat(stored.getId()).isNotNull();
        assertThat(saved).isSameAs(stored);
    }

    @Test
    void uploadDefaultsNullContentTypeToJpeg() throws IOException {
        when(images.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upload(png(), null);

        ArgumentCaptor<UploadedMarkerImage> captor = ArgumentCaptor.forClass(UploadedMarkerImage.class);
        verify(images).save(captor.capture());
        assertThat(captor.getValue().getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void uploadDefaultsBlankContentTypeToJpeg() throws IOException {
        when(images.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upload(png(), "   ");

        ArgumentCaptor<UploadedMarkerImage> captor = ArgumentCaptor.forClass(UploadedMarkerImage.class);
        verify(images).save(captor.capture());
        assertThat(captor.getValue().getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void uploadRejectsNullImage() {
        assertThatThrownBy(() -> service.upload(null, "image/png")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void uploadRejectsEmptyImage() {
        assertThatThrownBy(() -> service.upload(new byte[0], "image/png")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getReturnsStoredImage() {
        UUID id = UUID.randomUUID();
        UploadedMarkerImage m = UploadedMarkerImage.create("1".repeat(64), new byte[] {1, 2}, "image/png");
        when(images.findById(id)).thenReturn(Optional.of(m));

        assertThat(service.get(id)).containsSame(m);
        assertThat(m.getImage()).containsExactly(1, 2);
    }
}

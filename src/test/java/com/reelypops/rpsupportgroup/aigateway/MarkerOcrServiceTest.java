package com.reelypops.rpsupportgroup.aigateway;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reads one marker image's overlay text via the gateway's single-image OCR: base64-encodes the bytes into a
 * {@code data:} URL, returns the transcription, and fails open (empty) when the gateway is off/unavailable, the image is
 * empty, or the text is blank.
 */
class MarkerOcrServiceTest {

    private final RpAiGatewayClient gateway = mock(RpAiGatewayClient.class);
    private final MarkerOcrService service = new MarkerOcrService(gateway);

    @Test
    void readsTheTextAndEncodesTheImageAsADataUrl() {
        when(gateway.ocrImage(any()))
                .thenReturn(Optional.of(new RpAiGatewayClient.OcrImageResponse("GB AGENCY ENDE Sonntag")));

        Optional<String> text = service.readOverlayText(new byte[]{1, 2, 3}, "image/png", "glow.grp");

        assertThat(text).contains("GB AGENCY ENDE Sonntag");
        ArgumentCaptor<RpAiGatewayClient.OcrImageRequest> req =
                ArgumentCaptor.forClass(RpAiGatewayClient.OcrImageRequest.class);
        verify(gateway).ocrImage(req.capture());
        assertThat(req.getValue().igAccount()).isEqualTo("glow.grp");
        assertThat(req.getValue().imageUrl()).isEqualTo("data:image/png;base64,AQID"); // {1,2,3} base64 = AQID
    }

    @Test
    void defaultsTheContentTypeWhenBlankAndAllowsANullIgAccount() {
        when(gateway.ocrImage(any())).thenReturn(Optional.of(new RpAiGatewayClient.OcrImageResponse("ENDE")));

        Optional<String> text = service.readOverlayText(new byte[]{1, 2, 3}, "  ", null);

        assertThat(text).contains("ENDE");
        ArgumentCaptor<RpAiGatewayClient.OcrImageRequest> req =
                ArgumentCaptor.forClass(RpAiGatewayClient.OcrImageRequest.class);
        verify(gateway).ocrImage(req.capture());
        assertThat(req.getValue().igAccount()).isNull();
        assertThat(req.getValue().imageUrl()).startsWith("data:image/jpeg;base64,"); // defaulted content type
    }

    @Test
    void emptyWhenTheImageIsMissing() {
        assertThat(service.readOverlayText(null, "image/png", "g")).isEmpty();
        assertThat(service.readOverlayText(new byte[0], "image/png", "g")).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void emptyWhenTheGatewayIsOffOrReadsNothing() {
        when(gateway.ocrImage(any())).thenReturn(Optional.empty());

        assertThat(service.readOverlayText(new byte[]{9}, "image/png", "g")).isEmpty();
    }

    @Test
    void emptyWhenTheGatewayReturnsBlankText() {
        when(gateway.ocrImage(any())).thenReturn(Optional.of(new RpAiGatewayClient.OcrImageResponse("   ")));

        assertThat(service.readOverlayText(new byte[]{9}, "image/png", "g")).isEmpty();
    }
}

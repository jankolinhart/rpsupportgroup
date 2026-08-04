package com.reelypops.rpsupportgroup.aigateway;

import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Optional;

/**
 * Reads the overlay text off a SINGLE marker image via the AI gateway's single-image OCR, for the Vetting Portal's
 * hand-added markers ("add from grid" / "upload image"). A {@code TEXT_OVERLAY} marker built by hand MUST carry its OCR
 * target text — the client matches text-overlay markers on that text, not on the (varying) image — so this captures it
 * at add time. <strong>Fail-open</strong>: returns empty when the gateway is off, the call fails, or the image carries
 * no readable text; the Vetting Portal then leaves the operator to type the text into the editable field by hand.
 */
@Service
public class MarkerOcrService {

    private static final String DEFAULT_CONTENT_TYPE = "image/jpeg";

    private final RpAiGatewayClient gateway;

    public MarkerOcrService(RpAiGatewayClient gateway) {
        this.gateway = gateway;
    }

    /**
     * OCR one marker image's overlay text, or empty when unavailable / blank. {@code igAccount} is optional context for
     * the gateway's logging ({@code null} for an upload, which has no group scope).
     */
    public Optional<String> readOverlayText(byte[] image, String contentType, String igAccount) {
        if (image == null || image.length == 0) {
            return Optional.empty();
        }
        String type = (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        String dataUrl = "data:" + type + ";base64," + Base64.getEncoder().encodeToString(image);
        return gateway.ocrImage(new RpAiGatewayClient.OcrImageRequest(igAccount, dataUrl))
                .map(RpAiGatewayClient.OcrImageResponse::ocrText)
                .filter(text -> text != null && !text.isBlank());
    }
}

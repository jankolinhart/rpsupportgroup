package com.reelypops.rpsupportgroup.corpus;

/**
 * The OCR of a corpus representative image (Vetting Portal "add from grid"): the overlay text read off the picked
 * marker, or {@code null} when the AI gateway is off/unavailable or the image has no readable text (the operator then
 * types it in). Lets a hand-added {@code TEXT_OVERLAY} marker carry the OCR target the client matches on.
 */
public record MarkerOcrResponse(String ocrText) {
}
